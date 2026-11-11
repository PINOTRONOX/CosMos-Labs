using DropShip.Core;
var mode=args[0];var root=Path.GetFullPath(args[1]);Directory.CreateDirectory(root);var own=args[2];var book=new PairBook(Path.Combine(root,"devices.json"),own,()=>"테스트 기기 + C#");
if(mode=="notes"){DirectoryTests.Run(root,book);return;}
if(mode=="server"){
 var store=new TestStore(args[3]);ReceiverV2? server=null;V2Service? service=null;void Start(){service=new V2Service(book,new TransferRepository(Path.Combine(root,"incoming"),store));server=new ReceiverV2(service,int.Parse(args[4]));server.Start();Console.WriteLine("READY");}
 Start();while(true){var command=await Task.Run(()=>Console.ReadLine());if(command==null||command=="EXIT")break;if(command=="FOLDER_OFF"||command=="FOLDER_ON"){store.Ready=command=="FOLDER_ON";Console.WriteLine(command);}else if(command=="APPROVE"){foreach(var t in book.Pending)book.Decide(t.RequestId,true);Console.WriteLine("APPROVED");}else if(command=="REJECT"){foreach(var t in book.Pending)book.Decide(t.RequestId,false);Console.WriteLine("REJECTED");}else if(command.StartsWith("REVOKE ")){book.Remove(command[7..]);Console.WriteLine("REVOKED");}else if(command=="CLEAN"){service!.ClearIncomplete();Console.WriteLine("CLEANED");}else if(command=="STOP"){await server!.DisposeAsync();server=null;Console.WriteLine("STOPPED");}else if(command=="START"){Start();}}
 if(server!=null)await server.DisposeAsync();
}else {
 using var client=new ClientV2(book);var ep=DeviceEndpoint.Parse("127.0.0.1",args[3]);
 if(mode=="pair"){using var ct=new CancellationTokenSource(TimeSpan.FromSeconds(20));await client.Pair(ep,(name,code)=>Console.WriteLine("CODE "+code),ct.Token);Console.WriteLine("PAIRED");}
 else {using var ct=new CancellationTokenSource();var progress=new ProgressNow(p=>{if(p.Stage=="이어보내기")Console.WriteLine("RESUME "+p.Sent);if(mode=="cancel"&&p.Stage=="전송 중"&&p.Sent>=1048576)ct.Cancel();});try{var receipt=await client.Send(ep,args[4],args[5],args[6],args[7],args[8],mode=="directory",progress,ct.Token);Console.WriteLine("SAVED "+receipt.Name);}catch(V2Error e){Console.Error.WriteLine($"HTTP {e.Code} · {e.Message}");Environment.ExitCode=1;}catch(OperationCanceledException)when(mode=="cancel"){Console.WriteLine("CANCELLED");}}
}
class ProgressNow(Action<TransferProgress> action):IProgress<TransferProgress>{public void Report(TransferProgress p)=>action(p);}
class TestStore(string root):PublishStore {
 readonly FilePublishStore inner=new(root);public volatile bool Ready=true;
 public string? ReadinessIssue=>Ready?null:"받는 기기에서 먼저 받을 폴더를 선택해 주세요.";
 public string Scope=>inner.Scope;
 public string ReserveRoot(string name)=>inner.ReserveRoot(name);
 public void Directory(string path)=>inner.Directory(path);
 public TransferReceipt Publish(TransferState state,string payload)=>inner.Publish(state,payload);
}

static class DirectoryTests {
 public static void Run(string root,PairBook book){
  var wing=new string('2',32);var v40=new string('3',32);var token=new string('a',64);book.Put(wing,"LM-F100N",token);book.Put(v40,"LM-V409N",new string('b',64));var original=File.ReadAllText(Path.Combine(root,"devices.json"));var path=Path.Combine(root,"notes.json");var d=new DeviceDirectory(path);
  void Pass(bool ok,string text){if(!ok)throw new Exception(text);Console.WriteLine("PASS C# "+text);}
  d.SetAlias(wing," 라이 Wing ");d.SetAlias(v40,"라이 V40");d=new DeviceDirectory(path);
  Pass(d.Display(wing,"LM-F100N")=="LM-F100N (라이 Wing)"&&d.Display(v40,"LM-V409N")=="LM-V409N (라이 V40)","aliases survive restart and remain independent");
  Pass(new PairBook(Path.Combine(root,"devices.json"),book.OwnId,()=>"PC").Find(wing)?.Token==token&&File.ReadAllText(Path.Combine(root,"devices.json"))==original,"existing pairing credentials and model names preserved");
  d.SetAlias(wing,"");Pass(d.Display(wing,"LM-F100N")=="LM-F100N","empty alias restores unchanged model name");
  try{d.SetAlias(v40,"invalid\u0000alias");throw new Exception("accepted control character");}catch(TransferException){}
  Pass(new DeviceDirectory(path).Display(v40,"LM-V409N")=="LM-V409N (라이 V40)","invalid alias cannot replace persisted nickname");
  d.Seen(wing,DeviceEndpoint.Parse("192.168.45.105","8765"));Pass(new DeviceDirectory(path).Hint(wing)?.Address.ToString()=="192.168.45.105","private address hint survives restart");
  var publicPath=Path.Combine(root,"public-notes.json");V2Json.Write(publicPath,new Dictionary<string,DeviceNote>{{wing,new("","8.8.8.8",8765)}});Pass(new DeviceDirectory(publicPath).Hint(wing)==null,"untrusted public address hint rejected");
  var block=Path.Combine(root,"blocked");File.WriteAllText(block,"x");var broken=new DeviceDirectory(Path.Combine(block,"notes.json"));try{broken.SetAlias(wing,"unsaved");throw new Exception("write unexpectedly succeeded");}catch(IOException){}Pass(broken.Get(wing).Alias=="","failed save does not apply nickname in memory");
 }
}
