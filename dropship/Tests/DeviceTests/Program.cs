using DropShip.Core;
using System.Text.Json;
using System.Security.Cryptography;
var fixtures = Path.GetFullPath(args[0]);
var ep = DeviceEndpoint.Parse(args.Length > 2 ? args[2] : "127.0.0.1", args.Length > 3 ? args[3] : "18767");
using var client = new TransferClient();
await client.CheckAsync(ep, default);
Console.WriteLine("PASS receiver health (device installation is verified separately)");
var results = new List<TransferReceipt>();
foreach (var name in new[]{"01_test.txt","02_test.jpg","03_test.pdf","04_test.zip","01_test.txt","01_test.txt","한글 파일 + 공백.txt","empty.bin"}) {
    var f = Path.Combine(fixtures,name);
    if (!File.Exists(f)) File.WriteAllBytes(f, name == "empty.bin" ? [] : System.Text.Encoding.UTF8.GetBytes("Korean filename test"));
    var r = await client.SendAsync(ep,f,new QuietProgress(),default);
    if (r.Size != new FileInfo(f).Length || r.Sha256 != Convert.ToHexString(SHA256.HashData(await File.ReadAllBytesAsync(f))).ToLowerInvariant()) throw new Exception("receipt differs");
    results.Add(r);
    Console.WriteLine($"PASS receiver confirmed {r.Name} {r.Size} {r.Sha256}");
}
await File.WriteAllTextAsync(args[1], JsonSerializer.Serialize(results, new JsonSerializerOptions { WriteIndented = true }));
Console.WriteLine("PASS ALL receiver size/hash receipts (not direct filesystem inspection)");
class QuietProgress : IProgress<TransferProgress> { public void Report(TransferProgress p) { } }
