import com.cosrai.dropship.*
import java.io.File
fun main(args:Array<String>){val mode=args[0];val root=File(args[1]).apply{mkdirs()};val own=args[2];val book=PairBook(File(root,"devices.json"),own,{"테스트 기기 + Kotlin"})
if(mode=="notes"){directoryTests(root,book,own);return}
if(mode=="server"){
 val events=object:ReceiveEvents{override fun progress(name:String,bytes:Long,total:Long){};override fun completed(receipt:Receipt){};override fun failed(message:String){}}
 val store=TestStore(File(args[3]));var server:ReceiverV2?=null;fun start(){server=ReceiverV2(V2Service(book,TransferRepository(File(root,"incoming"),store),events),args[4].toInt());println("READY")};start()
 while(true){val cmd=readlnOrNull()?:break;when{cmd=="EXIT"->break;cmd=="FOLDER_OFF"||cmd=="FOLDER_ON"->{store.ready=cmd=="FOLDER_ON";println(cmd)};cmd=="APPROVE"->{book.pending().forEach{book.decide(it.requestId,true)};println("APPROVED")};cmd=="REJECT"->{book.pending().forEach{book.decide(it.requestId,false)};println("REJECTED")};cmd.startsWith("REVOKE ")->{book.remove(cmd.substring(7));println("REVOKED")};cmd=="STOP"->{server?.close();server=null;println("STOPPED")};cmd=="START"->start()}}
 server?.close()
}else{val client=ClientV2(book);val ep=DeviceEndpoint.parse("127.0.0.1",args[3]);val control=SendControl()
 if(mode=="pair"){client.pair(ep,{_,code->println("CODE $code")},control);println("PAIRED")}else{val events=object:SendEvents{override fun progress(stage:String,bytes:Long,total:Long){if(stage=="이어보내기")println("RESUME $bytes");if(mode=="cancel"&&stage=="전송 중"&&bytes>=1048576)control.cancel()}};try{val r=client.send(ep,args[4],File(args[5]),args[6],args[7],args[8],mode=="directory",events,control);println("SAVED ${r.name}")}catch(e:V2Error){System.err.println("HTTP ${e.code} · ${e.message}");kotlin.system.exitProcess(1)}catch(e:java.io.InterruptedIOException){if(mode!="cancel")throw e;println("CANCELLED")}}
}}

class TestStore(root:File):PublishStore {private val inner=FilePublishStore(root);@Volatile var ready=true
 override val scope get()=inner.scope
 override val readinessIssue get()=if(ready)null else "받는 기기에서 먼저 받을 폴더를 선택해 주세요."
 override fun reserveRoot(name:String)=inner.reserveRoot(name)
 override fun directory(relative:String)=inner.directory(relative)
 override fun publish(state:TransferState,payload:File)=inner.publish(state,payload)
}

fun directoryTests(root:File,book:PairBook,own:String){
 val wing="2".repeat(32);val v40="3".repeat(32);val token="a".repeat(64);book.put(wing,"LM-F100N",token);book.put(v40,"LM-V409N","b".repeat(64));val original=File(root,"devices.json").readText();val path=File(root,"notes.json");var d=DeviceDirectory(path)
 fun pass(ok:Boolean,text:String){check(ok){text};println("PASS Kotlin $text")}
 d.setAlias(wing," 라이 Wing ");d.setAlias(v40,"라이 V40");d=DeviceDirectory(path)
 pass(d.display(wing,"LM-F100N")=="LM-F100N (라이 Wing)"&&d.display(v40,"LM-V409N")=="LM-V409N (라이 V40)","aliases survive restart and remain independent")
 pass(PairBook(File(root,"devices.json"),own,{"Phone"}).find(wing)?.token==token&&File(root,"devices.json").readText()==original,"existing pairing credentials and model names preserved")
 d.setAlias(wing,"");pass(d.display(wing,"LM-F100N")=="LM-F100N","empty alias restores unchanged model name")
 var rejected=false;try{d.setAlias(v40,"invalid\u0000alias")}catch(e:IllegalArgumentException){rejected=true};pass(rejected&&DeviceDirectory(path).display(v40,"LM-V409N")=="LM-V409N (라이 V40)","invalid alias cannot replace persisted nickname")
 d.seen(wing,DeviceEndpoint.parse("192.168.45.105","8765"));pass(DeviceDirectory(path).hint(wing)?.ip=="192.168.45.105","private address hint survives restart")
 val publicFile=File(root,"public-notes.json");V2Json.write(publicFile,org.json.JSONObject().put(wing,org.json.JSONObject().put("address","8.8.8.8").put("port",8765)).toString());pass(DeviceDirectory(publicFile).hint(wing)==null,"untrusted public address hint rejected")
 val block=File(root,"blocked");block.writeText("x");val broken=DeviceDirectory(File(block,"notes.json"));rejected=false;try{broken.setAlias(wing,"unsaved")}catch(e:java.io.IOException){rejected=true};pass(rejected&&broken.get(wing).alias.isEmpty(),"failed save does not apply nickname in memory")
}
