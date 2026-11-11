using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
namespace DropShip.Core;
public static class V2Json {
    public static readonly JsonSerializerOptions Options=new(){PropertyNamingPolicy=JsonNamingPolicy.CamelCase,PropertyNameCaseInsensitive=true,WriteIndented=true};
    public static T Read<T>(string path) => JsonSerializer.Deserialize<T>(File.ReadAllText(path),Options)!;
    public static void Write(string path,object value){Directory.CreateDirectory(Path.GetDirectoryName(path)!);var tmp=path+".tmp";using(var s=new FileStream(tmp,FileMode.Create,FileAccess.Write,FileShare.None)){JsonSerializer.Serialize(s,value,value.GetType(),Options);s.Flush(true);}File.Move(tmp,path,true);}
    public static string Hash(byte[] bytes)=>Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
    public static bool Hex(string s,int length)=>s.Length==length&&s.All(Uri.IsHexDigit);
    public static string PathName(string relative){if(relative.Length>1024||relative.Contains('\\')||relative.StartsWith('/')||relative.Split('/').Length>32)throw new V2Error(400,"폴더 경로가 올바르지 않습니다.");foreach(var part in relative.Split('/'))ReceiverServer.ValidateName(part);return relative;}
}
public sealed class V2Error(int code,string message):IOException(message){public int Code{get;}=code;}
public record ApprovedDevice(string Id,string Name,string Token);
public record PairTicket(string RequestId,string DeviceId,string Name,string Nonce,string Code,DateTime Expires){public string Status{get;set;}="pending";public string Token{get;set;}="";}
public sealed class PairBook {
    readonly object gate=new();readonly string path;readonly List<ApprovedDevice> devices;readonly List<PairTicket> requests=[];
    public string OwnId{get;} public Func<string> OwnName{get;}
    public PairBook(string path,string ownId,Func<string> ownName){this.path=path;OwnId=ownId;OwnName=ownName;try{devices=V2Json.Read<List<ApprovedDevice>>(path);}catch{devices=[];}}
    public IReadOnlyList<ApprovedDevice> Devices{get{lock(gate)return devices.ToArray();}}
    public IReadOnlyList<PairTicket> Pending{get{lock(gate){Expire();return requests.Where(x=>x.Status=="pending").ToArray();}}}
    void Expire()=>requests.RemoveAll(x=>x.Expires<DateTime.UtcNow);
    public ApprovedDevice? Find(string id){lock(gate)return devices.FirstOrDefault(x=>x.Id==id);}
    public void Put(string id,string name,string token){if(!V2Json.Hex(id,32)||id==OwnId||!V2Json.Hex(token,64))throw new V2Error(400,"기기 등록 정보가 올바르지 않습니다.");name=DiscoveryWire.Name(name);lock(gate){devices.RemoveAll(x=>x.Id==id);if(devices.Count>=128)throw new V2Error(409,"등록 가능한 기기 수를 초과했습니다.");devices.Add(new(id,name,token));V2Json.Write(path,devices);}}
    public void Remove(string id){lock(gate){devices.RemoveAll(x=>x.Id==id);requests.RemoveAll(x=>x.DeviceId==id);V2Json.Write(path,devices);}}
    public PairTicket Request(string id,string name,string nonce){if(!V2Json.Hex(id,32)||id==OwnId||!V2Json.Hex(nonce,32))throw new V2Error(400,"기기 요청 정보가 올바르지 않습니다.");name=DiscoveryWire.Name(name);lock(gate){Expire();var old=requests.FirstOrDefault(x=>x.DeviceId==id&&x.Nonce==nonce);if(old!=null)return old;if(requests.Count>=8)throw new V2Error(429,"연결 요청이 많습니다. 잠시 후 다시 시도하세요.");var ticket=new PairTicket(Guid.NewGuid().ToString("N"),id,name,nonce,RandomNumberGenerator.GetInt32(100000,1000000).ToString(),DateTime.UtcNow.AddMinutes(2));requests.Add(ticket);return ticket;}}
    public void Decide(string requestId,bool approved){lock(gate){Expire();var ticket=requests.FirstOrDefault(x=>x.RequestId==requestId&&x.Status=="pending")??throw new V2Error(404,"연결 요청 시간이 지났습니다.");if(approved){ticket.Token=Convert.ToHexString(RandomNumberGenerator.GetBytes(32)).ToLowerInvariant();Put(ticket.DeviceId,ticket.Name,ticket.Token);ticket.Status="approved";}else ticket.Status="rejected";}}
    public object Poll(string requestId,string nonce){lock(gate){Expire();var t=requests.FirstOrDefault(x=>x.RequestId==requestId&&x.Nonce==nonce)??throw new V2Error(404,"연결 요청이 만료되었습니다.");if(t.Status=="approved"&&Find(t.DeviceId)?.Token!=t.Token)throw new V2Error(403,"등록이 해제되었습니다.");return new{status=t.Status,deviceId=OwnId,name=OwnName(),token=t.Status=="approved"?t.Token:""};}}
    public string Authenticate(IReadOnlyDictionary<string,string> headers){var id=headers.GetValueOrDefault("X-DropShip-Device","");var token=headers.GetValueOrDefault("X-DropShip-Token","");lock(gate){var device=devices.FirstOrDefault(x=>x.Id==id);if(headers.GetValueOrDefault("X-DropShip-Target","")!=OwnId||device==null||!V2Json.Hex(token,64)||!CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(device.Token),Encoding.ASCII.GetBytes(token)))throw new V2Error(403,"먼저 양쪽 기기에서 연결을 승인해 주세요.");return id;}}
}
public record TransferSpec(string TransferId,string BatchId,string RelativePath,long Size,string Sha256,bool Directory);
public sealed class TransferState {
    public string Owner{get;set;}="";public TransferSpec Spec{get;set;}=null!;public string ActualPath{get;set;}="";public long Offset{get;set;}
    public TransferReceipt? Receipt{get;set;} public DateTime Updated{get;set;}=DateTime.UtcNow;
}
public interface PublishStore {
    string Scope{get;}
    string? ReadinessIssue=>null;
    string ReserveRoot(string name);
    void Directory(string relative);
    TransferReceipt Publish(TransferState state,string payload);
}
public sealed class FilePublishStore(string root):PublishStore {
    public string Scope=>Path.GetFullPath(root);
    string Resolve(string relative){V2Json.PathName(relative);var current=Path.GetFullPath(root);System.IO.Directory.CreateDirectory(current);if((File.GetAttributes(current)&FileAttributes.ReparsePoint)!=0)throw new V2Error(400,"연결된 폴더에는 저장할 수 없습니다.");foreach(var part in relative.Split('/')){current=Path.Combine(current,part);if((File.Exists(current)||System.IO.Directory.Exists(current))&&(File.GetAttributes(current)&FileAttributes.ReparsePoint)!=0)throw new V2Error(400,"연결된 파일·폴더에는 저장할 수 없습니다.");}return current;}
    public string ReserveRoot(string name){for(int i=0;i<10000;i++){var unique=ReceiverServer.CollisionName(name,i);var p=Resolve(unique);if(File.Exists(p)||System.IO.Directory.Exists(p))continue;System.IO.Directory.CreateDirectory(p);return unique;}throw new V2Error(409,"동일한 폴더명이 너무 많습니다.");}
    public void Directory(string relative)=>System.IO.Directory.CreateDirectory(Resolve(relative));
    public TransferReceipt Publish(TransferState state,string payload){var basePath=Resolve(state.ActualPath);System.IO.Directory.CreateDirectory(Path.GetDirectoryName(basePath)!);var journal=payload+".publish.json";string target;
        if(File.Exists(journal))target=V2Json.Read<string>(journal);else{target=basePath;for(int i=1;File.Exists(target)||System.IO.Directory.Exists(target);i++)target=Path.Combine(Path.GetDirectoryName(basePath)!,ReceiverServer.CollisionName(Path.GetFileName(basePath),i));V2Json.Write(journal,target);}
        // Recover a published file, or choose a fresh name if another file took the reserved name.
        if(File.Exists(target)){using var f=File.OpenRead(target);if(f.Length==state.Spec.Size&&Convert.ToHexString(SHA256.HashData(f)).ToLowerInvariant()==state.Spec.Sha256)return new(Path.GetFileName(target),f.Length,state.Spec.Sha256,target);}
        if(File.Exists(target)||System.IO.Directory.Exists(target)){for(int i=1;;i++){var candidate=Path.Combine(Path.GetDirectoryName(basePath)!,ReceiverServer.CollisionName(Path.GetFileName(basePath),i));if(!File.Exists(candidate)&&!System.IO.Directory.Exists(candidate)){target=candidate;break;}}V2Json.Write(journal,target);}
        var pending=Path.Combine(Path.GetDirectoryName(target)!,".dropship-pending-"+Guid.NewGuid().ToString("N"));bool created=false;
        try{using(var output=new FileStream(pending,FileMode.CreateNew,FileAccess.Write,FileShare.None)){created=true;using(var input=File.OpenRead(payload))input.CopyTo(output,131072);output.Flush(true);}using(var f=File.OpenRead(pending)){if(f.Length!=state.Spec.Size||Convert.ToHexString(SHA256.HashData(f)).ToLowerInvariant()!=state.Spec.Sha256)throw new V2Error(422,"저장 파일 검증에 실패했습니다.");}Resolve(Path.GetRelativePath(root,target).Replace('\\','/'));File.Move(pending,target,false);}finally{if(created&&File.Exists(pending))File.Delete(pending);}
        return new(Path.GetFileName(target),state.Spec.Size,state.Spec.Sha256,target);
    }
}
public sealed class TransferRepository {
    readonly string cache;readonly PublishStore store;readonly Dictionary<string,string> roots;int activeCount;
    public TransferRepository(string baseCache,PublishStore store){this.store=store;cache=Path.Combine(baseCache,V2Json.Hash(Encoding.UTF8.GetBytes(store.Scope))[..16]);System.IO.Directory.CreateDirectory(cache);try{roots=V2Json.Read<Dictionary<string,string>>(Path.Combine(cache,"roots.json"));}catch{roots=[];}foreach(var p in System.IO.Directory.GetFiles(cache,"*.json")){if(!V2Json.Hex(Path.GetFileNameWithoutExtension(p),32))continue;var s=V2Json.Read<TransferState>(p);if(s.Receipt==null)activeCount++;else if(File.GetLastWriteTimeUtc(p)<DateTime.UtcNow.AddDays(-30)){File.Delete(p);File.Delete(DataPath(s.Spec.TransferId)+".publish.json");}}}
    public string? ReadinessIssue=>store.ReadinessIssue;
    string StatePath(string id)=>Path.Combine(cache,id+".json");string DataPath(string id)=>Path.Combine(cache,id+".data");
    string Actual(string owner,TransferSpec spec){var parts=spec.RelativePath.Split('/');if(!spec.Directory&&parts.Length==1)return spec.RelativePath;var key=owner+"|"+spec.BatchId+"|"+parts[0];if(!roots.TryGetValue(key,out var mapped)){mapped=store.ReserveRoot(parts[0]);roots[key]=mapped;V2Json.Write(Path.Combine(cache,"roots.json"),roots);}return string.Join('/',new[]{mapped}.Concat(parts.Skip(1)));}
    public TransferState Load(string owner,string id){if(!V2Json.Hex(id,32)||!File.Exists(StatePath(id)))throw new V2Error(404,"이어받을 작업이 없습니다.");var state=V2Json.Read<TransferState>(StatePath(id));if(state.Owner!=owner)throw new V2Error(403,"다른 기기의 작업에는 접근할 수 없습니다.");if(state.Receipt==null&&!state.Spec.Directory){var data=DataPath(id);if(!File.Exists(data)||new FileInfo(data).Length<state.Offset){state.Offset=0;using var f=File.Create(data);}using var file=new FileStream(data,FileMode.Open,FileAccess.Write);file.SetLength(state.Offset);}return state;}
    void Save(TransferState state){state.Updated=DateTime.UtcNow;V2Json.Write(StatePath(state.Spec.TransferId),state);}
    public TransferState Begin(string owner,TransferSpec spec){if(ReadinessIssue is string issue)throw new V2Error(409,issue);if(!V2Json.Hex(spec.TransferId,32)||!V2Json.Hex(spec.BatchId,32)||spec.Size<0||!V2Json.Hex(spec.Sha256,64)||spec.Sha256!=spec.Sha256.ToLowerInvariant())throw new V2Error(400,"파일 정보가 올바르지 않습니다.");V2Json.PathName(spec.RelativePath);if(File.Exists(StatePath(spec.TransferId))){var old=Load(owner,spec.TransferId);if(old.Spec!=spec)throw new V2Error(409,"원본 파일이 바뀌었습니다. 다시 추가해 주세요.");return old;}
        if(activeCount>=4096)throw new V2Error(409,"수신 기록이 많습니다. 미완료 수신을 정리해 주세요.");var free=new DriveInfo(Path.GetPathRoot(cache)!).AvailableFreeSpace;if(!spec.Directory&&spec.Size>Math.Max(0,(free-4194304)/2))throw new V2Error(507,"수신 기기에 파일 크기의 약 두 배 여유 공간이 필요합니다.");var state=new TransferState{Owner=owner,Spec=spec,ActualPath=Actual(owner,spec)};if(spec.Directory){store.Directory(state.ActualPath);state.Receipt=new(Path.GetFileName(state.ActualPath),0,spec.Sha256,state.ActualPath);}else{using var f=File.Create(DataPath(spec.TransferId));}Save(state);if(!spec.Directory)activeCount++;return state;
    }
    public TransferState Chunk(string owner,string id,long offset,byte[] body,string hash){var state=Load(owner,id);if(state.Receipt!=null)return state;if(state.Spec.Directory||offset!=state.Offset||body.Length==0||body.Length>1048576||body.Length>state.Spec.Size-offset)throw new V2Error(409,"이어받기 위치가 다릅니다. 다시 시도해 주세요.");if(V2Json.Hash(body)!=hash)throw new V2Error(422,"전송 조각 검증에 실패했습니다.");using(var file=new FileStream(DataPath(id),FileMode.Open,FileAccess.Write)){file.Position=state.Offset;try{file.Write(body);file.Flush(true);state.Offset+=body.Length;Save(state);}catch{file.SetLength(offset);throw;}}return state;}
    public TransferReceipt Commit(string owner,string id){var state=Load(owner,id);if(state.Receipt!=null)return state.Receipt;if(state.Offset!=state.Spec.Size)throw new V2Error(409,"파일 수신이 아직 완료되지 않았습니다.");bool valid;using(var input=File.OpenRead(DataPath(id))){valid=Convert.ToHexString(SHA256.HashData(input)).ToLowerInvariant()==state.Spec.Sha256;}if(!valid){state.Offset=0;using(var f=new FileStream(DataPath(id),FileMode.Open,FileAccess.Write)){f.SetLength(0);}Save(state);throw new V2Error(422,"파일 검증에 실패했습니다. 처음부터 다시 전송해 주세요.");}
        var receipt=store.Publish(state,DataPath(id));state.Receipt=receipt;Save(state);activeCount--;File.Delete(DataPath(id));return receipt;
    }
    public int ClearIncomplete(){int count=0;foreach(var path in System.IO.Directory.GetFiles(cache,"*.json")){if(!V2Json.Hex(Path.GetFileNameWithoutExtension(path),32))continue;var state=V2Json.Read<TransferState>(path);if(state.Receipt!=null)continue;File.Delete(DataPath(state.Spec.TransferId));File.Delete(path);activeCount--;count++;}return count;}
}
