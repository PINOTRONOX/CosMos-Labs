using DropShip.Core;
using System.Net;
using System.Text;
const string id="11111111111111111111111111111111";
if(args[0]=="respond"){using var responder=new DiscoveryResponder(id,()=>"라이 PC + 책상","windows",18767,18776);Console.WriteLine("READY");await Task.Delay(Timeout.Infinite);}
else if(args[0]=="scan"){var found=await DiscoveryScanner.ScanAsync(id,default,[IPAddress.Loopback],18775);if(found.Count!=1||found[0].Name!="라이 폰 + Wing"||found[0].Endpoint.Port!=18765)throw new Exception("interop offer mismatch");Console.WriteLine("PASS C# discovers Kotlin name/id/port/source address");}
else {
var nonce=new string('a',32);var bytes=Encoding.UTF8.GetBytes(DiscoveryWire.Offer(nonce,id,"라이 PC + 책상","windows",8765));
if(DiscoveryWire.Parse(bytes,IPAddress.Loopback,nonce,"other")?.Name!="라이 PC + 책상")throw new Exception();Console.WriteLine("PASS C# unicode name roundtrip");
foreach(var test in new[]{(bytes,IPAddress.Loopback,"wrong","other"),(bytes,IPAddress.Loopback,nonce,id),(bytes,IPAddress.Parse("8.8.8.8"),nonce,"other"),(new byte[1025],IPAddress.Loopback,nonce,"other"),(Encoding.UTF8.GetBytes("DROPSHIP_OFFER|1|bad"),IPAddress.Loopback,nonce,"other")})if(DiscoveryWire.Parse(test.Item1,test.Item2,test.Item3,test.Item4)!=null)throw new Exception("bad offer accepted");Console.WriteLine("PASS C# ignores stale nonce / self / public IP / oversized / malformed offers");
var first=DiscoveryWire.Parse(bytes,IPAddress.Parse("192.168.1.2"),nonce,"other")!;var next=DiscoveryWire.Parse(bytes,IPAddress.Parse("192.168.1.9"),nonce,"other")!;if(first.Id!=next.Id||first.Endpoint==next.Endpoint)throw new Exception();Console.WriteLine("PASS C# stable device ID resolves changed address");
if(DiscoveryWire.QueryNonce("DROPSHIP_DISCOVER|1|"+nonce)!=nonce||DiscoveryWire.QueryNonce("DROPSHIP_DISCOVER|2|"+nonce)!=null)throw new Exception();Console.WriteLine("PASS C# discovery query validation");
}
