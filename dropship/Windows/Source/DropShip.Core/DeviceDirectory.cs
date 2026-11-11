namespace DropShip.Core;

public sealed record DeviceNote(string Alias="",string Address="",int Port=8765);
// Presentation and address hints never modify pairing credentials or authenticate a peer.
public sealed class DeviceDirectory {
    readonly string path;readonly Dictionary<string,DeviceNote> notes;
    public DeviceDirectory(string path){this.path=path;try{notes=V2Json.Read<Dictionary<string,DeviceNote>>(path);}catch{notes=[];}}
    public static string Alias(string value){var alias=value.Trim();if(alias=="")return "";return DiscoveryWire.Name(alias);}
    public DeviceNote Get(string id)=>notes.GetValueOrDefault(id,new());
    public string Display(string id,string model){var alias=Get(id).Alias;return alias==""?model:$"{model} ({alias})";}
    public void SetAlias(string id,string alias){if(!V2Json.Hex(id,32))throw new V2Error(400,"기기 ID가 올바르지 않습니다.");var updated=Get(id) with{Alias=Alias(alias)};Persist(id,updated);}
    public void Seen(string id,DeviceEndpoint endpoint){if(!V2Json.Hex(id,32))throw new V2Error(400,"기기 ID가 올바르지 않습니다.");Persist(id,Get(id) with{Address=endpoint.Address.ToString(),Port=endpoint.Port});}
    public DeviceEndpoint? Hint(string id){var n=Get(id);try{return DeviceEndpoint.Parse(n.Address,n.Port.ToString());}catch{return null;}}
    public void Remove(string id){var next=new Dictionary<string,DeviceNote>(notes);next.Remove(id);V2Json.Write(path,next);notes.Remove(id);}
    void Persist(string id,DeviceNote note){var next=new Dictionary<string,DeviceNote>(notes){[id]=note};V2Json.Write(path,next);notes[id]=note;}
}

public sealed record RemoteInfo(string Id,string Name,bool ReceiveReady,string ReceiveMessage);
