using System.Net;
using System.Net.Sockets;
using System.Net.NetworkInformation;
using System.Text;
namespace DropShip.Core;
public record Peer(string Id,string Name,string Kind,DeviceEndpoint Endpoint) { public override string ToString()=>$"{Name} · {Kind} · {Endpoint.Address}"; }
public static class DiscoveryWire {
    public const int Port=8766;
    public static string Name(string name){name=name.Trim();if(name.Length==0||name.Length>40||Encoding.UTF8.GetByteCount(name)>120||name.Any(char.IsControl))throw new TransferException("기기 이름은 제어문자 없이 1~40자로 입력해 주세요.");return name;}
    static bool Hex(string s)=>s.Length==32&&s.All(Uri.IsHexDigit);
    public static string? QueryNonce(string query){var p=query.Split('|');return p.Length==3&&p[0]=="DROPSHIP_DISCOVER"&&p[1]=="1"&&Hex(p[2])?p[2]:null;}
    public static string Offer(string nonce,string id,string name,string kind,int port)=>$"DROPSHIP_OFFER|1|{nonce}|{id}|{Uri.EscapeDataString(Name(name))}|{kind}|{port}|0.5.0";
    public static Peer? Parse(byte[] bytes,IPAddress source,string nonce,string ownId){try{if(bytes.Length>1024)return null;var p=Encoding.UTF8.GetString(bytes).Split('|');if(p.Length!=8||p[0]!="DROPSHIP_OFFER"||p[1]!="1"||p[2]!=nonce||!Hex(p[3])||p[3]==ownId||p[5] is not("windows" or "android")||p[7]!="0.5.0")return null;return new(p[3],Name(Uri.UnescapeDataString(p[4])),p[5],DeviceEndpoint.Parse(source.ToString(),p[6]));}catch{return null;}}
}
public sealed class DiscoveryResponder : IDisposable {
    readonly UdpClient udp; readonly CancellationTokenSource stop=new(); readonly Task loop;
    public DiscoveryResponder(string id,Func<string> name,string kind,int tcpPort=8765,int udpPort=8766){udp=new(new IPEndPoint(IPAddress.Any,udpPort));loop=Task.Run(async()=>{try{while(!stop.IsCancellationRequested){var r=await udp.ReceiveAsync(stop.Token);if(r.Buffer.Length>1024)continue;var nonce=DiscoveryWire.QueryNonce(Encoding.ASCII.GetString(r.Buffer));if(nonce==null)continue;try{DeviceEndpoint.Parse(r.RemoteEndPoint.Address.ToString(),"1");var b=Encoding.UTF8.GetBytes(DiscoveryWire.Offer(nonce,id,name(),kind,tcpPort));await udp.SendAsync(b,r.RemoteEndPoint,stop.Token);}catch(TransferException){}}}catch(Exception) when(stop.IsCancellationRequested){} });}
    public void Dispose(){stop.Cancel();udp.Dispose();try{loop.GetAwaiter().GetResult();}catch{}stop.Dispose();}
}
public static class DiscoveryScanner {
    public static IEnumerable<IPAddress> Broadcasts(){var addresses=new HashSet<IPAddress>{IPAddress.Broadcast};foreach(var nic in NetworkInterface.GetAllNetworkInterfaces().Where(n=>n.OperationalStatus==OperationalStatus.Up)){foreach(var u in nic.GetIPProperties().UnicastAddresses.Where(u=>u.Address.AddressFamily==AddressFamily.InterNetwork)){try{DeviceEndpoint.Parse(u.Address.ToString(),"1");var a=u.Address.GetAddressBytes();var m=u.IPv4Mask.GetAddressBytes();addresses.Add(new IPAddress(a.Zip(m,(x,y)=>(byte)(x|~y)).ToArray()));}catch{}}}return addresses;}
    public static async Task<List<Peer>> ScanAsync(string ownId,CancellationToken ct,IEnumerable<IPAddress>? targets=null,int port=8766){
        using var udp=new UdpClient(AddressFamily.InterNetwork){EnableBroadcast=true};udp.Client.Bind(new IPEndPoint(IPAddress.Any,0));
        using var duration=CancellationTokenSource.CreateLinkedTokenSource(ct);duration.CancelAfter(TimeSpan.FromSeconds(3));var nonce=Guid.NewGuid().ToString("N");var query=Encoding.ASCII.GetBytes("DROPSHIP_DISCOVER|1|"+nonce);var peers=new Dictionary<string,Peer>();
        var destinations=(targets??Broadcasts()).Distinct().Take(16).ToArray();
        var sender=Task.Run(async()=>{for(int i=0;i<3;i++){foreach(var address in destinations){try{await udp.SendAsync(query,new IPEndPoint(address,port),duration.Token);}catch(SocketException){} }await Task.Delay(650,duration.Token);}},duration.Token);
        try{while(!duration.IsCancellationRequested){var r=await udp.ReceiveAsync(duration.Token);var peer=DiscoveryWire.Parse(r.Buffer,r.RemoteEndPoint.Address,nonce,ownId);if(peer!=null&&peers.Count<64)peers.TryAdd(peer.Id,peer);}}catch(OperationCanceledException){}
        finally{duration.Cancel();try{await sender;}catch(OperationCanceledException){}}
        ct.ThrowIfCancellationRequested();return peers.Values.OrderBy(p=>p.Name).ToList();
    }
}
