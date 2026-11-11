using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text.Json;

namespace DropShip.Core;

public record TransferProgress(string Stage, long Sent, long Total);
public record TransferReceipt(string Name, long Size, string Sha256, string Path);
public sealed class TransferException(string message) : Exception(message);

// Device discovery/pairing can supply this endpoint without changing the UI or transport.
public record DeviceEndpoint(IPAddress Address, int Port)
{
    public static DeviceEndpoint Parse(string ip, string port)
    {
        if (!IPAddress.TryParse(ip.Trim(), out var address) || address.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork)
            throw new TransferException("상대 기기 화면의 IPv4 주소를 입력해 주세요. 예: 192.168.0.25");
        var b = address.GetAddressBytes();
        if (!(b[0] == 10 || b[0] == 192 && b[1] == 168 || b[0] == 172 && b[1] >= 16 && b[1] <= 31 || b[0] == 169 && b[1] == 254 || b[0] == 127))
            throw new TransferException("같은 Wi-Fi의 로컬 IP 주소만 사용할 수 있습니다.");
        if (!int.TryParse(port, out var p) || p < 1 || p > 65535)
            throw new TransferException("Port는 1~65535 사이 숫자로 입력해 주세요.");
        return new(address, p);
    }
    public Uri Uri(string path) => new($"http://{Address}:{Port}{path}");
}

public sealed class TransferClient : IDisposable
{
    private readonly HttpClient http = new(new SocketsHttpHandler {
        UseProxy = false, AllowAutoRedirect = false, ConnectTimeout = TimeSpan.FromSeconds(5),
        PooledConnectionLifetime = TimeSpan.FromMinutes(2)
    }) { Timeout = Timeout.InfiniteTimeSpan };
    private static readonly JsonSerializerOptions JsonOptions = new() { PropertyNameCaseInsensitive = true };
    public async Task CheckAsync(DeviceEndpoint endpoint, CancellationToken ct)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromSeconds(7));
        using var r = await http.GetAsync(endpoint.Uri("/v1/health"), HttpCompletionOption.ResponseHeadersRead, timeout.Token);
        r.EnsureSuccessStatusCode();
        var data = await ReadJson(r, timeout.Token);
        if (data.GetProperty("service").GetString() != "DropShip" || data.GetProperty("protocol").GetInt32() != 1)
            throw new TransferException("해당 주소의 DropShip 버전이 맞지 않습니다.");
    }
    private static async Task<JsonElement> ReadJson(HttpResponseMessage response, CancellationToken ct)
    {
        // A wrong IP/server must not send unlimited response content into memory.
        await using var stream = await response.Content.ReadAsStreamAsync(ct);
        using var memory = new MemoryStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = await stream.ReadAsync(b, ct)) > 0) {
            if (memory.Length + n > 16384) throw new TransferException("상대 기기 응답 형식이 올바르지 않습니다.");
            memory.Write(b, 0, n);
        }
        using var doc = JsonDocument.Parse(memory.ToArray());
        return doc.RootElement.Clone();
    }
    public async Task<TransferReceipt> SendAsync(DeviceEndpoint endpoint, string filePath,
        IProgress<TransferProgress> progress, CancellationToken ct)
    {
        await CheckAsync(endpoint, ct);
        // Keep one read-only handle locked against edits during hashing and upload.
        await using var file = new FileStream(filePath, FileMode.Open, FileAccess.Read, FileShare.Read,
            131072, FileOptions.Asynchronous | FileOptions.SequentialScan);
        progress.Report(new("원본 파일 확인 중", 0, file.Length));
        string digest = Convert.ToHexString(await SHA256.HashDataAsync(file, ct)).ToLowerInvariant();
        file.Position = 0;
        using var transportTimeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        transportTimeout.CancelAfter(TimeSpan.FromSeconds(60));
        using var req = new HttpRequestMessage(HttpMethod.Post, endpoint.Uri("/v1/files"));
        req.Version = HttpVersion.Version11;
        req.Headers.ExpectContinue = false;
        req.Headers.Add("X-DropShip-Filename", Uri.EscapeDataString(System.IO.Path.GetFileName(filePath)));
        req.Headers.Add("X-DropShip-SHA256", digest);
        req.Content = new FileContent(file, progress, transportTimeout);
        using var r = await http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, transportTimeout.Token);
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromSeconds(30));
        var data = await ReadJson(r, timeout.Token);
        if (!r.IsSuccessStatusCode)
            throw new TransferException(data.TryGetProperty("error", out var error) ? error.GetString() ?? "수신에 실패했습니다." : "수신에 실패했습니다.");
        var receipt = JsonSerializer.Deserialize<TransferReceipt>(data.GetRawText(), JsonOptions)
            ?? throw new TransferException("수신 결과를 확인할 수 없습니다.");
        if (string.IsNullOrWhiteSpace(receipt.Name) || string.IsNullOrWhiteSpace(receipt.Path) || receipt.Size != file.Length || !string.Equals(receipt.Sha256, digest, StringComparison.OrdinalIgnoreCase))
            throw new TransferException("파일 검증 결과가 일치하지 않습니다. 다시 전송해 주세요.");
        return receipt;
    }
    public void Dispose() => http.Dispose();
    private sealed class FileContent(FileStream file, IProgress<TransferProgress> progress, CancellationTokenSource timeout) : HttpContent
    {
        protected override bool TryComputeLength(out long length) { length = file.Length; return true; }
        protected override Task SerializeToStreamAsync(Stream stream, TransportContext? context) => Copy(stream, timeout.Token);
        protected override Task SerializeToStreamAsync(Stream stream, TransportContext? context, CancellationToken ct) => Copy(stream, ct);
        private async Task Copy(Stream stream, CancellationToken ct)
        {
            using var linked = CancellationTokenSource.CreateLinkedTokenSource(timeout.Token, ct);
            byte[] buffer = new byte[131072]; long sent = 0; var last = Environment.TickCount64;
            int n;
            while ((n = await file.ReadAsync(buffer, linked.Token)) > 0) {
                // Abort stalled writes, while allowing large transfers with continued progress.
                using var idle = CancellationTokenSource.CreateLinkedTokenSource(linked.Token);
                idle.CancelAfter(TimeSpan.FromSeconds(30));
                await stream.WriteAsync(buffer.AsMemory(0, n), idle.Token);
                sent += n;
                timeout.CancelAfter(TimeSpan.FromSeconds(60));
                if (Environment.TickCount64 - last > 100 || sent == file.Length) {
                    progress.Report(new("전송 중", sent, file.Length)); last = Environment.TickCount64;
                }
            }
            progress.Report(new("상대 기기 저장·검증 대기 중", sent, file.Length));
            // HttpClient waits for response headers. Bound this phase too.
            linked.Token.ThrowIfCancellationRequested();
            timeout.CancelAfter(TimeSpan.FromSeconds(60));
        }
    }
}
