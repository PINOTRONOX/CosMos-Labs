using DropShip.Core;
using System.Security.Cryptography;
using System.Net;
using System.Net.Sockets;
using System.Text;

var fixtures = Path.GetFullPath(args[0]);
var received = Path.GetFullPath(args[1]);
var ep = DeviceEndpoint.Parse("127.0.0.1", "18765");
using var client = new TransferClient();
var progress = new ImmediateProgress();
int passed = 0;
void Pass(string label) { passed++; Console.WriteLine("PASS " + label); }
void Assert(bool yes, string message) { if (!yes) throw new Exception(message); }
await client.CheckAsync(ep, default); Pass("health / protocol handshake");
foreach (var file in Directory.GetFiles(fixtures).OrderBy(x => x)) {
    var r = await client.SendAsync(ep, file, progress, default);
    var original = await File.ReadAllBytesAsync(file);
    var saved = await File.ReadAllBytesAsync(Path.Combine(received, r.Name));
    Assert(original.AsSpan().SequenceEqual(saved), "stored bytes differ");
    Assert(r.Sha256 == Convert.ToHexString(SHA256.HashData(original)).ToLowerInvariant(), "hash differs");
    Pass($"{Path.GetFileName(file)} {r.Size} bytes stored / hash / byte equality");
}
var txt = Path.Combine(fixtures, "01_test.txt");
var firstHash = SHA256.HashData(await File.ReadAllBytesAsync(Path.Combine(received, "01_test.txt")));
var duplicate = await client.SendAsync(ep, txt, progress, default);
Assert(duplicate.Name == "01_test (1).txt", "collision filename");
var originalAgain = SHA256.HashData(await File.ReadAllBytesAsync(Path.Combine(received, "01_test.txt")));
Assert(firstHash.AsSpan().SequenceEqual(originalAgain), "original overwritten");
Pass("duplicate filename keeps original");
var third = await client.SendAsync(ep, txt, progress, default);
Assert(third.Name == "01_test (2).txt", "second collision filename"); Pass("second duplicate suffix (2)");
var unicode = Path.Combine(fixtures, "한글 파일 + 공백.txt");
await File.WriteAllTextAsync(unicode, "라이의 DropShip 파일 전송 ✓");
var ur = await client.SendAsync(ep, unicode, progress, default);
Assert(ur.Name == Path.GetFileName(unicode), "unicode altered");
Pass("Korean / spaces / plus filename");
var empty = Path.Combine(fixtures, "empty.bin"); File.WriteAllBytes(empty, []);
var er = await client.SendAsync(ep, empty, progress, default);
Assert(er.Size == 0 && File.Exists(Path.Combine(received, "empty.bin")), "empty transfer"); Pass("zero-byte file");
foreach (var name in new[] { "simulate-no-space.bin", "simulate-write-failure.bin" }) {
    var f = Path.Combine(fixtures, name); File.WriteAllBytes(f, new byte[1024]);
    try { await client.SendAsync(ep, f, progress, default); throw new Exception("failure not rejected"); }
    catch (TransferException e) { Assert(e.Message.Contains(name.Contains("no-space") ? "공간" : "저장"), "error message"); }
    Pass(name + " mapped to Korean error");
}
try { await client.CheckAsync(DeviceEndpoint.Parse("127.0.0.1", "18766"), default); throw new Exception("offline accepted"); }
catch (HttpRequestException) { Pass("offline receiver returns failure"); }
foreach (var p in new[] { ("bad", "8765"), ("192.168.1.1", "0"), ("192.168.1.1", "65536"), ("8.8.8.8", "8765") }) {
    try { DeviceEndpoint.Parse(p.Item1, p.Item2); throw new Exception("invalid accepted"); }
    catch (TransferException) { Pass("invalid/public endpoint rejected " + p); }
}
try { await client.SendAsync(ep, Path.Combine(fixtures, "missing.bin"), progress, default); throw new Exception("missing accepted"); }
catch (FileNotFoundException) { Pass("missing local file"); }
var cancelled = new CancellationTokenSource(); cancelled.Cancel();
try { await client.SendAsync(ep, txt, progress, cancelled.Token); throw new Exception("cancel accepted"); }
catch (OperationCanceledException) { Pass("user cancellation"); }
// Cancel after transport has written its first block; an incomplete file must not publish.
var midPath = Path.Combine(fixtures,"mid-cancel-csharp.bin");
File.Copy(Path.Combine(fixtures,"04_test.zip"),midPath,true);
using (var mid = new CancellationTokenSource()) {
    try { await client.SendAsync(ep,midPath,new CancellingProgress(mid),mid.Token); throw new Exception("mid cancel accepted"); }
    catch(OperationCanceledException) { Pass("mid-upload cancellation closes C# sender"); }
}
await Task.Delay(250);
Assert(!File.Exists(Path.Combine(received,"mid-cancel-csharp.bin")),"cancelled file published");
Assert(!Directory.GetFiles(received,".partial-*").Any(),"cancelled partial retained");
Pass("mid-upload cancellation leaves no published or partial file");
File.Delete(midPath);
async Task<string> Raw(string filename, string hash, int declared, byte[] body, bool cut = false) {
    using var tcp = new TcpClient(); await tcp.ConnectAsync("127.0.0.1", 18765);
    var s = tcp.GetStream();
    var header = $"POST /v1/files HTTP/1.1\r\nHost: localhost\r\nContent-Length: {declared}\r\nX-DropShip-Filename: {filename}\r\nX-DropShip-SHA256: {hash}\r\nConnection: close\r\n\r\n";
    await s.WriteAsync(Encoding.ASCII.GetBytes(header)); await s.WriteAsync(body);
    if (cut) { tcp.Client.Shutdown(SocketShutdown.Send); }
    using var reader = new StreamReader(s); return await reader.ReadToEndAsync();
}
string zeros = new('0', 64);
var mismatch = await Raw("bad-hash.bin", zeros, 3, new byte[]{1,2,3});
Assert(mismatch.StartsWith("HTTP/1.1 422"), "bad hash accepted");
Assert(!File.Exists(Path.Combine(received, "bad-hash.bin")), "bad hash published"); Pass("hash mismatch rejected and no completed file");
var interrupted = await Raw("cut.bin", zeros, 10000, new byte[10], true);
Assert(interrupted.Contains("연결"), "disconnect message missing"); Pass("mid-upload disconnect");
var traversal = await Raw("..%2Fescape.txt", zeros, 0, []);
Assert(traversal.StartsWith("HTTP/1.1 400"), "traversal accepted"); Pass("path traversal rejected");
await Task.Delay(100);
Assert(!Directory.GetFiles(received, ".partial-*").Any(), "partial not removed"); Pass("failed upload cleanup");
await client.CheckAsync(ep, default); Pass("receiver still healthy after failures");
Console.WriteLine($"TOTAL {passed} passed");
class ImmediateProgress : IProgress<TransferProgress> { public void Report(TransferProgress p) { } }

class CancellingProgress(CancellationTokenSource source):IProgress<TransferProgress>{public void Report(TransferProgress p){if(p.Stage=="전송 중"&&p.Sent>0)source.Cancel();}}
