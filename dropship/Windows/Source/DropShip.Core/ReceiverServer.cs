using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Collections.Concurrent;
namespace DropShip.Core;
public record ReceiveProgress(string Name, long Bytes, long Total);
public sealed class ReceiverServer(string folder, int port = 8765) : IAsyncDisposable
{
    readonly CancellationTokenSource stop = new();
    readonly TcpListener listener = new(IPAddress.Any, port);
    readonly SemaphoreSlim slots = new(4);
    readonly ConcurrentDictionary<TcpClient, Task> clients = new();
    Task? accept; int uploading;
    public event Action<ReceiveProgress>? Progress;
    public event Action<TransferReceipt>? Completed;
    public event Action<string>? Failed;
    public void Start() { Directory.CreateDirectory(folder); listener.Start(8); accept = Accept(); }
    async Task Accept() {
        try { while (!stop.IsCancellationRequested) {
            await slots.WaitAsync(stop.Token);
            TcpClient tcp;
            try { tcp = await listener.AcceptTcpClientAsync(stop.Token); } catch { slots.Release(); throw; }
            // Register before processing starts, so shutdown can always observe and drain it.
            var ready = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            clients[tcp] = Run(tcp, ready.Task); ready.SetResult();
        }} catch (Exception) when (stop.IsCancellationRequested) { }
    }
    async Task Run(TcpClient tcp, Task ready) {
        await ready; string? temp = null; bool owns = false, committed = false;
        using var idle = CancellationTokenSource.CreateLinkedTokenSource(stop.Token); idle.CancelAfter(TimeSpan.FromSeconds(30));
        try {
            var stream = tcp.GetStream(); var request = (await Line(stream, idle.Token)).Split(' ');
            if (request.Length != 3 || request[2] != "HTTP/1.1") throw new Failure(400,"지원하지 않는 요청입니다.");
            var headers = new Dictionary<string,string>(StringComparer.OrdinalIgnoreCase); int headerBytes = 0;
            while (true) { var line = await Line(stream,idle.Token); headerBytes += line.Length;
                if (headerBytes > 16384 || headers.Count > 40) throw new Failure(431,"요청 정보가 너무 큽니다.");
                if (line.Length == 0) break; int at = line.IndexOf(':');
                if (at <= 0 || !headers.TryAdd(line[..at],line[(at+1)..].Trim())) throw new Failure(400,"중복되거나 잘못된 요청 정보입니다.");
            }
            if (request[0] == "GET" && request[1] == "/v1/health") { await Respond(stream,200,new {service="DropShip",protocol=1,version="0.4.0",port},idle.Token); return; }
            if (request[0] != "POST" || request[1] != "/v1/files") throw new Failure(404,"지원하지 않는 전송 경로입니다.");
            if (headers.ContainsKey("Transfer-Encoding")) throw new Failure(400,"파일 크기가 포함된 전송만 지원합니다.");
            if (!headers.TryGetValue("Content-Length",out var rawSize) || !long.TryParse(rawSize,out var size)) throw new Failure(411,"파일 크기를 확인할 수 없습니다.");
            if (size < 0) throw new Failure(400,"파일 크기가 올바르지 않습니다.");
            var name = ValidateName(Uri.UnescapeDataString(headers.GetValueOrDefault("X-DropShip-Filename","").Replace("+"," ")));
            var expected = headers.GetValueOrDefault("X-DropShip-SHA256","").ToLowerInvariant();
            if (expected.Length != 64 || expected.Any(c => !Uri.IsHexDigit(c))) throw new Failure(400,"SHA-256 정보가 올바르지 않습니다.");
            if (Interlocked.CompareExchange(ref uploading,1,0) != 0) throw new Failure(409,"다른 파일을 받는 중입니다. 완료 후 다시 시도해 주세요."); owns = true;
            if (size > Math.Max(0,new DriveInfo(Path.GetPathRoot(Path.GetFullPath(folder))!).AvailableFreeSpace-4194304)) throw new Failure(507,"PC 저장 공간이 부족합니다.");
            temp = Path.Combine(folder,".dropship-partial-"+Guid.NewGuid().ToString("N"));
            string hash;
            await using (var file = new FileStream(temp,FileMode.CreateNew,FileAccess.Write,FileShare.None,131072,true)) {
                using var digest = IncrementalHash.CreateHash(HashAlgorithmName.SHA256); var b = new byte[131072];long received = 0; long last = 0;
                Progress?.Invoke(new(name,0,size));
                while (received < size) { int n = await stream.ReadAsync(b.AsMemory(0,(int)Math.Min(b.Length,size-received)),idle.Token); if (n == 0) throw new EndOfStreamException();
                    await file.WriteAsync(b.AsMemory(0,n),idle.Token); digest.AppendData(b,0,n);received += n; idle.CancelAfter(TimeSpan.FromSeconds(30));
                    if (Environment.TickCount64-last>150 || received==size) { Progress?.Invoke(new(name,received,size));last=Environment.TickCount64; }
                }
                await file.FlushAsync(idle.Token); hash=Convert.ToHexString(digest.GetHashAndReset()).ToLowerInvariant();
            }
            if (hash != expected) throw new Failure(422,"파일 검증에 실패했습니다. 다시 전송해 주세요.");
            await using (var verify = File.OpenRead(temp)) { var stored=Convert.ToHexString(await SHA256.HashDataAsync(verify,idle.Token)).ToLowerInvariant(); if(verify.Length!=size || stored!=hash)throw new Failure(422,"저장된 파일 검증에 실패했습니다."); }
            stop.Token.ThrowIfCancellationRequested(); string actual=name, destination="";
            for(int i=0;;i++) { actual=CollisionName(name,i);destination=Path.Combine(folder,actual);try{File.Move(temp,destination,false);break;}catch(IOException) when(File.Exists(destination)){if(i>=10000)throw new IOException("파일명 중복이 너무 많습니다.");} }
            temp=null;committed=true; var receipt=new TransferReceipt(actual,size,hash,Path.GetFullPath(destination)); Completed?.Invoke(receipt);
            await Respond(stream,201,new {name=actual,size,sha256=hash,path=receipt.Path},idle.Token);
        } catch(Exception e) {
            int code=e is Failure f?f.Code: e is IOException io && (io.HResult&0xffff)==112?507:500;
            var message=committed?"파일은 저장됐지만 상대 기기에 결과를 전달하지 못했습니다.":e is Failure?e.Message:e is EndOfStreamException || e is SocketException?"파일 전송 중 연결이 끊어졌습니다.":e is OperationCanceledException?"수신이 중지되거나 응답 시간이 초과되었습니다.":"파일을 저장하지 못했습니다. 저장 공간과 연결을 확인해 주세요.";
            if(owns)Failed?.Invoke(message);
            try { using var responseTimeout=new CancellationTokenSource(TimeSpan.FromSeconds(2));await Respond(tcp.GetStream(),code,new{error=message},responseTimeout.Token); } catch { }
        } finally { if(temp!=null)try{File.Delete(temp);}catch{} if(owns)Interlocked.Exchange(ref uploading,0);tcp.Dispose();slots.Release();clients.TryRemove(tcp,out _); }
    }
    public static string ValidateName(string name) {
        var stem=name.Split('.')[0].TrimEnd(' ').ToUpperInvariant();
        if(string.IsNullOrWhiteSpace(name)||name.EndsWith('.')||name.EndsWith(' ')||Encoding.UTF8.GetByteCount(name)>220||name.Any(c=>c<' '||c==127||"/\\:*?\"<>|".Contains(c))||new[]{"CON","PRN","AUX","NUL","CONIN$","CONOUT$"}.Contains(stem)||System.Text.RegularExpressions.Regex.IsMatch(stem,@"^(COM|LPT)[1-9¹²³]$"))throw new Failure(400,"지원하지 않는 파일명입니다. 파일명을 바꿔 주세요.");return name;
    }
    public static string CollisionName(string name,int index){int at=name.LastIndexOf('.');if(at<=0)at=name.Length;return index==0?name:$"{name[..at]} ({index}){name[at..]}";}
    static async Task<string> Line(Stream s,CancellationToken ct){var bytes=new List<byte>();byte[] one=new byte[1];while(bytes.Count<=4096){if(await s.ReadAsync(one,ct)==0)throw new EndOfStreamException();if(one[0]==10){if(bytes.Count==0||bytes[^1]!=13)throw new Failure(400,"요청 형식이 올바르지 않습니다.");return Encoding.ASCII.GetString(bytes.ToArray(),0,bytes.Count-1);}bytes.Add(one[0]);}throw new Failure(431,"요청 정보가 너무 큽니다.");}
    static async Task Respond(Stream stream,int code,object value,CancellationToken ct){var body=JsonSerializer.SerializeToUtf8Bytes(value);var header=Encoding.ASCII.GetBytes($"HTTP/1.1 {code} {(code<300?"OK":"Error")}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: {body.Length}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n");await stream.WriteAsync(header,ct);await stream.WriteAsync(body,ct);}
    public async ValueTask DisposeAsync(){stop.Cancel();listener.Stop();foreach(var tcp in clients.Keys)tcp.Dispose();if(accept!=null)await accept;await Task.WhenAll(clients.Values);stop.Dispose();slots.Dispose();}
    sealed class Failure(int code,string message):IOException(message){public int Code{get;}=code;}
}
