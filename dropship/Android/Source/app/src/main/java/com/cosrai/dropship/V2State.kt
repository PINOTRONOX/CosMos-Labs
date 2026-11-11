package com.cosrai.dropship
import org.json.JSONObject
import org.json.JSONArray
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

object V2Json {
    fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    fun hashFile(file:File):String {val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use{input->val b=ByteArray(131072);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)}};return digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}}
    fun hex(s:String,n:Int)=s.length==n&&s.all{it in "0123456789abcdefABCDEF"}
    fun id()=UUID.randomUUID().toString().replace("-","")
    fun write(file:File,value:String){file.parentFile!!.mkdirs();val temp=File(file.path+".tmp");FileOutputStream(temp).use{it.write(value.toByteArray(Charsets.UTF_8));it.fd.sync()};Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)}
    fun path(relative:String):String {if(relative.length>1024||relative.contains('\\')||relative.startsWith('/')||relative.split('/').size>32)throw V2Error(400,"폴더 경로가 올바르지 않습니다.");relative.split('/').forEach{ReceiverServer.validateName(it)};return relative}
}
class V2Error(val code:Int,override val message:String):IOException(message)
data class ApprovedDevice(val id:String,val name:String,val token:String)
data class PairTicket(val requestId:String,val deviceId:String,val name:String,val nonce:String,val code:String,val expires:Long,var status:String="pending",var token:String="")
class PairBook(private val file:File,val ownId:String,val ownName:()->String) {
    private val devices=mutableListOf<ApprovedDevice>();private val requests=mutableListOf<PairTicket>()
    init{try{val a=JSONArray(file.readText());for(i in 0 until a.length()){val j=a.getJSONObject(i);devices.add(ApprovedDevice(j.getString("id"),j.getString("name"),j.getString("token")))}}catch(_:Exception){}}
    @Synchronized fun devices()=devices.toList()
    private fun expire(){requests.removeAll{it.expires<System.currentTimeMillis()}}
    @Synchronized fun pending():List<PairTicket>{expire();return requests.filter{it.status=="pending"}.toList()}
    @Synchronized fun find(id:String)=devices.firstOrNull{it.id==id}
    private fun save(){V2Json.write(file,JSONArray().apply{devices.forEach{put(JSONObject().put("id",it.id).put("name",it.name).put("token",it.token))}}.toString())}
    @Synchronized fun put(id:String,name:String,token:String){if(!V2Json.hex(id,32)||id==ownId||!V2Json.hex(token,64))throw V2Error(400,"기기 등록 정보가 올바르지 않습니다.");val n=DiscoveryWire.name(name);devices.removeAll{it.id==id};if(devices.size>=128)throw V2Error(409,"등록 가능한 기기 수를 초과했습니다.");devices.add(ApprovedDevice(id,n,token));save()}
    @Synchronized fun remove(id:String){devices.removeAll{it.id==id};requests.removeAll{it.deviceId==id};save()}
    @Synchronized fun request(id:String,name:String,nonce:String):PairTicket{if(!V2Json.hex(id,32)||id==ownId||!V2Json.hex(nonce,32))throw V2Error(400,"기기 요청 정보가 올바르지 않습니다.");val n=DiscoveryWire.name(name);expire();requests.firstOrNull{it.deviceId==id&&it.nonce==nonce}?.let{return it};if(requests.size>=8)throw V2Error(429,"연결 요청이 많습니다. 잠시 후 다시 시도하세요.");return PairTicket(V2Json.id(),id,n,nonce,(100000+SecureRandom().nextInt(900000)).toString(),System.currentTimeMillis()+120000).also{requests.add(it)}}
    @Synchronized fun decide(requestId:String,approve:Boolean){expire();val t=requests.firstOrNull{it.requestId==requestId&&it.status=="pending"}?:throw V2Error(404,"연결 요청 시간이 지났습니다.");if(approve){val bytes=ByteArray(32);SecureRandom().nextBytes(bytes);t.token=bytes.joinToString(""){"%02x".format(it.toInt() and 255)};put(t.deviceId,t.name,t.token);t.status="approved"}else t.status="rejected"}
    @Synchronized fun poll(id:String,nonce:String):JSONObject {expire();val t=requests.firstOrNull{it.requestId==id&&it.nonce==nonce}?:throw V2Error(404,"연결 요청이 만료되었습니다.");if(t.status=="approved"&&find(t.deviceId)?.token!=t.token)throw V2Error(403,"등록이 해제되었습니다.");return JSONObject().put("status",t.status).put("deviceId",ownId).put("name",ownName()).put("token",if(t.status=="approved")t.token else "")}
    @Synchronized fun authenticate(headers:Map<String,String>):String {val id=headers["x-dropship-device"]?:"";val token=headers["x-dropship-token"]?:"";val device=find(id);if(headers["x-dropship-target"]!=ownId||device==null||!V2Json.hex(token,64)||!MessageDigest.isEqual(device.token.toByteArray(),token.toByteArray()))throw V2Error(403,"먼저 양쪽 기기에서 연결을 승인해 주세요.");return id}
}
data class TransferSpec(val transferId:String,val batchId:String,val relativePath:String,val size:Long,val sha256:String,val directory:Boolean){fun json()=JSONObject().put("transferId",transferId).put("batchId",batchId).put("relativePath",relativePath).put("size",size).put("sha256",sha256).put("directory",directory);companion object{fun parse(j:JSONObject)=TransferSpec(j.getString("transferId"),j.getString("batchId"),j.getString("relativePath"),j.getLong("size"),j.getString("sha256"),j.getBoolean("directory"))}}
data class TransferState(val owner:String,val spec:TransferSpec,val actualPath:String,var offset:Long=0,var receipt:Receipt?=null){fun json()=JSONObject().put("owner",owner).put("spec",spec.json()).put("actualPath",actualPath).put("offset",offset).put("receipt",receipt?.let{receiptJson(it)}?:JSONObject.NULL);companion object{fun parse(j:JSONObject)=TransferState(j.getString("owner"),TransferSpec.parse(j.getJSONObject("spec")),j.getString("actualPath"),j.getLong("offset"),if(j.isNull("receipt"))null else receiptParse(j.getJSONObject("receipt")))}}
fun receiptJson(r:Receipt)=JSONObject().put("name",r.name).put("size",r.size).put("sha256",r.sha256).put("path",r.path)
fun receiptParse(j:JSONObject)=Receipt(j.getString("name"),j.getLong("size"),j.getString("sha256"),j.getString("path"))
interface PublishStore {val scope:String;val readinessIssue:String? get()=null;fun reserveRoot(name:String):String;fun directory(relative:String);fun publish(state:TransferState,payload:File):Receipt}
class FilePublishStore(private val root:File):PublishStore {
    override val scope=root.absolutePath
    private fun resolve(relative:String):File {V2Json.path(relative);root.mkdirs();if(Files.isSymbolicLink(root.toPath()))throw V2Error(400,"연결된 폴더에는 저장할 수 없습니다.");var current=root;relative.split('/').forEach{current=File(current,it);if(Files.isSymbolicLink(current.toPath()))throw V2Error(400,"연결된 파일·폴더에는 저장할 수 없습니다.")};return current}
    override fun reserveRoot(name:String):String{for(i in 0 until 10000){val n=ReceiverServer.collisionName(name,i);val p=resolve(n);if(p.exists())continue;if(!p.mkdirs())throw IOException("Cannot create folder");return n};throw V2Error(409,"동일한 폴더명이 너무 많습니다.")}
    override fun directory(relative:String){val p=resolve(relative);if(!p.isDirectory&&!p.mkdirs())throw IOException("Cannot create folder")}
    override fun publish(state:TransferState,payload:File):Receipt {
        val base=resolve(state.actualPath);base.parentFile!!.mkdirs();val journal=File(payload.path+".publish.json");var target:File
        if(journal.exists())target=File(JSONObject(journal.readText()).getString("target")) else {target=base;var i=0;while(target.exists()){i++;target=File(base.parentFile,ReceiverServer.collisionName(base.name,i))};V2Json.write(journal,JSONObject().put("target",target.absolutePath).toString())}
        if(target.isFile&&target.length()==state.spec.size&&V2Json.hashFile(target)==state.spec.sha256)return Receipt(target.name,target.length(),state.spec.sha256,target.absolutePath)
        if(target.exists()){var i=1;while(true){val candidate=File(base.parentFile,ReceiverServer.collisionName(base.name,i++));if(!candidate.exists()){target=candidate;break}};V2Json.write(journal,JSONObject().put("target",target.absolutePath).toString())}
        val pending=File(target.parentFile,".dropship-pending-"+V2Json.id());var created=false
        try{val channel=java.nio.channels.FileChannel.open(pending.toPath(),java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE);created=true;channel.use{ch->val output=java.nio.channels.Channels.newOutputStream(ch);payload.inputStream().use{it.copyTo(output,131072)};output.flush();ch.force(true)};check(pending.length()==state.spec.size&&V2Json.hashFile(pending)==state.spec.sha256);resolve(target.relativeTo(root).invariantSeparatorsPath);Files.move(pending.toPath(),target.toPath())}finally{if(created)pending.delete()};return Receipt(target.name,state.spec.size,state.spec.sha256,target.absolutePath)
    }
}
class TransferRepository(baseCache:File,private val store:PublishStore) {
    private val cache=File(baseCache,V2Json.hash(store.scope.toByteArray()).take(16)).apply{mkdirs()}
    val gate:Any=locks.computeIfAbsent(cache.absolutePath){Any()}
    companion object{private val locks=java.util.concurrent.ConcurrentHashMap<String,Any>();private val counts=java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.atomic.AtomicInteger>()}
    private val activeCount=counts.computeIfAbsent(cache.absolutePath){synchronized(gate){var count=0;cache.listFiles()!!.filter{it.extension=="json"&&V2Json.hex(it.nameWithoutExtension,32)}.forEach{f->val s=TransferState.parse(JSONObject(f.readText()));if(s.receipt==null)count++ else if(f.lastModified()<System.currentTimeMillis()-30L*86400000){f.delete();File(dataFile(s.spec.transferId).path+".publish.json").delete()}};java.util.concurrent.atomic.AtomicInteger(count)}}
    private val rootsFile=File(cache,"roots.json")
    private fun stateFile(id:String)=File(cache,"$id.json");private fun dataFile(id:String)=File(cache,"$id.data")
    val readinessIssue:String? get()=store.readinessIssue
    private fun actual(owner:String,spec:TransferSpec):String{val roots=try{JSONObject(rootsFile.readText())}catch(_:Exception){JSONObject()};val parts=spec.relativePath.split('/');if(!spec.directory&&parts.size==1)return spec.relativePath;val key="$owner|${spec.batchId}|${parts[0]}";var mapped=roots.optString(key,"");if(mapped.isEmpty()){mapped=store.reserveRoot(parts[0]);roots.put(key,mapped);V2Json.write(rootsFile,roots.toString())};return (listOf(mapped)+parts.drop(1)).joinToString("/")}
    fun load(owner:String,id:String):TransferState{if(!V2Json.hex(id,32)||!stateFile(id).exists())throw V2Error(404,"이어받을 작업이 없습니다.");val state=TransferState.parse(JSONObject(stateFile(id).readText()));if(state.owner!=owner)throw V2Error(403,"다른 기기의 작업에는 접근할 수 없습니다.");if(state.receipt==null&&!state.spec.directory){val file=dataFile(id);if(!file.exists()||file.length()<state.offset){state.offset=0;file.writeBytes(byteArrayOf())};RandomAccessFile(file,"rw").use{it.setLength(state.offset)}};return state}
    private fun save(state:TransferState)=V2Json.write(stateFile(state.spec.transferId),state.json().toString())
    fun begin(owner:String,spec:TransferSpec):TransferState{readinessIssue?.let{throw V2Error(409,it)};if(!V2Json.hex(spec.transferId,32)||!V2Json.hex(spec.batchId,32)||spec.size<0||!V2Json.hex(spec.sha256,64)||spec.sha256!=spec.sha256.lowercase())throw V2Error(400,"파일 정보가 올바르지 않습니다.");V2Json.path(spec.relativePath);if(stateFile(spec.transferId).exists()){val old=load(owner,spec.transferId);if(old.spec!=spec)throw V2Error(409,"원본 파일이 바뀌었습니다. 다시 추가해 주세요.");return old};if(activeCount.get()>=4096)throw V2Error(409,"미완료 수신을 정리해 주세요.");if(!spec.directory&&spec.size>maxOf(0,(cache.usableSpace-4194304)/2))throw V2Error(507,"수신 기기에 파일 크기의 약 두 배 여유 공간이 필요합니다.");val state=TransferState(owner,spec,actual(owner,spec));if(spec.directory){store.directory(state.actualPath);state.receipt=Receipt(state.actualPath.substringAfterLast('/'),0,spec.sha256,state.actualPath)}else dataFile(spec.transferId).writeBytes(byteArrayOf());save(state);if(!spec.directory)activeCount.incrementAndGet();return state}
    fun chunk(owner:String,id:String,offset:Long,bytes:ByteArray,hash:String):TransferState{val state=load(owner,id);if(state.receipt!=null)return state;if(state.spec.directory||offset!=state.offset||bytes.isEmpty()||bytes.size>1048576||bytes.size>state.spec.size-offset)throw V2Error(409,"이어받기 위치가 다릅니다.");if(V2Json.hash(bytes)!=hash)throw V2Error(422,"전송 조각 검증에 실패했습니다.");RandomAccessFile(dataFile(id),"rw").use{file->file.seek(offset);try{file.write(bytes);file.fd.sync();state.offset+=bytes.size;save(state)}catch(e:Exception){file.setLength(offset);throw e}};return state}
    fun commit(owner:String,id:String):Receipt{val state=load(owner,id);state.receipt?.let{return it};if(state.offset!=state.spec.size)throw V2Error(409,"파일 수신이 아직 완료되지 않았습니다.");if(V2Json.hashFile(dataFile(id))!=state.spec.sha256){state.offset=0;dataFile(id).writeBytes(byteArrayOf());save(state);throw V2Error(422,"파일 검증에 실패했습니다. 처음부터 다시 전송해 주세요.")};val receipt=store.publish(state,dataFile(id));state.receipt=receipt;save(state);activeCount.decrementAndGet();dataFile(id).delete();return receipt}
    fun clearIncomplete():Int {var count=0;cache.listFiles()!!.filter{it.extension=="json"&&V2Json.hex(it.nameWithoutExtension,32)}.forEach{val state=TransferState.parse(JSONObject(it.readText()));if(state.receipt==null){dataFile(state.spec.transferId).delete();it.delete();activeCount.decrementAndGet();count++}};return count}
}
