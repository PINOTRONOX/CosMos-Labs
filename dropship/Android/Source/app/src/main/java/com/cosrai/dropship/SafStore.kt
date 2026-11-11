package com.cosrai.dropship
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as D
import java.io.*
import org.json.JSONObject

/** A user-selected SAF folder preserves nested and empty directories without broad storage access. */
class SafStore(private val context:Context,private val tree:()->String):PublishStore {
    private val resolver=context.contentResolver
    private val selectedTree=tree()
    override val scope="SAF:"+selectedTree
    override val readinessIssue:String? get(){
        if(selectedTree.isEmpty())return "받는 폰에서 먼저 ‘받을 폴더 선택’을 눌러 주세요."
        return try{
            val treeUri=Uri.parse(selectedTree)
            if(resolver.persistedUriPermissions.none{it.uri==treeUri&&it.isReadPermission&&it.isWritePermission})return "받는 폰의 폴더 권한이 없습니다. ‘받을 폴더 선택’으로 다시 지정해 주세요."
            resolver.query(root(),arrayOf(D.Document.COLUMN_FLAGS),null,null,null)?.use{c->if(!c.moveToFirst()||(c.getInt(0) and D.Document.FLAG_DIR_SUPPORTS_CREATE)==0)return "받는 폰에서 파일을 만들 수 있는 내부 저장소 폴더를 선택해 주세요."}?:return "받는 폰의 폴더를 열 수 없습니다. 수신 폴더를 다시 선택해 주세요."
            null
        }catch(_:Exception){"받는 폰의 폴더 권한을 확인할 수 없습니다. 수신 폴더를 다시 선택해 주세요."}
    }
    private fun root():Uri{val s=selectedTree;if(s.isEmpty())throw V2Error(409,"폰에서 먼저 ‘받을 폴더 선택’을 눌러 주세요.");val uri=Uri.parse(s);return D.buildDocumentUriUsingTree(uri,D.getTreeDocumentId(uri))}
    data class Child(val name:String,val mime:String,val uri:Uri)
    private fun children(parent:Uri):List<Child>{val uri=D.buildChildDocumentsUriUsingTree(parent,D.getDocumentId(parent));val result=mutableListOf<Child>();resolver.query(uri,arrayOf(D.Document.COLUMN_DOCUMENT_ID,D.Document.COLUMN_DISPLAY_NAME,D.Document.COLUMN_MIME_TYPE),null,null,null)?.use{c->while(c.moveToNext()){result.add(Child(c.getString(1),c.getString(2),D.buildDocumentUriUsingTree(parent,c.getString(0))))}}?:throw IOException("Cannot list receive folder");return result}
    private fun directoryUri(relative:String):Uri{if(relative.isEmpty())return root();V2Json.path(relative);var parent=root();for(part in relative.split('/')){val child=children(parent).firstOrNull{it.name==part};parent=if(child!=null){if(child.mime!=D.Document.MIME_TYPE_DIR)throw V2Error(409,"같은 이름의 파일이 있습니다.");child.uri}else D.createDocument(resolver,parent,D.Document.MIME_TYPE_DIR,part)?:throw IOException("Cannot create receive folder")};return parent}
    override fun reserveRoot(name:String):String{val parent=root();val names=children(parent).map{it.name}.toSet();for(i in 0 until 10000){val unique=ReceiverServer.collisionName(name,i);if(unique in names)continue;val created=D.createDocument(resolver,parent,D.Document.MIME_TYPE_DIR,unique)?:throw IOException("Cannot create receive folder");return display(created)};throw V2Error(409,"동일한 폴더명이 너무 많습니다.")}
    override fun directory(relative:String){directoryUri(relative)}
    private fun display(uri:Uri):String{resolver.query(uri,arrayOf(D.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())return it.getString(0)};throw IOException("Cannot read saved filename")}
    private fun matches(uri:Uri,size:Long,hash:String):Boolean=try{val digest=java.security.MessageDigest.getInstance("SHA-256");var total=0L;resolver.openInputStream(uri)?.use{input->val b=ByteArray(131072);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n);total+=n}}?:throw IOException("Cannot read saved file");total==size&&digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}==hash}catch(_:Exception){false}
    override fun publish(state:TransferState,payload:File):Receipt {
        val parentPath=state.actualPath.substringBeforeLast('/',"");val desired=state.actualPath.substringAfterLast('/');val parent=directoryUri(parentPath);val journal=File(payload.path+".publish.json");var info=if(journal.exists())JSONObject(journal.readText()) else JSONObject()
        var actual=info.optString("name","");var pendingString=info.optString("pending","")
        if(actual.isNotEmpty()){val existing=children(parent).firstOrNull{it.name==actual};if(existing!=null&&existing.mime!=D.Document.MIME_TYPE_DIR&&matches(existing.uri,state.spec.size,state.spec.sha256))return Receipt(actual,state.spec.size,state.spec.sha256,listOf(parentPath,actual).filter{it.isNotEmpty()}.joinToString("/"))}
        if(actual.isEmpty()||children(parent).any{it.name==actual}){val names=children(parent).map{it.name}.toSet();var index=0;actual=desired;while(actual in names){index++;actual=ReceiverServer.collisionName(desired,index)};info.put("name",actual);V2Json.write(journal,info.toString())}
        var pending=if(pendingString.isNotEmpty())Uri.parse(pendingString) else null
        if(pending!=null)try{display(pending)}catch(_:Exception){pending=null}
        if(pending==null){val mime=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(desired.substringAfterLast('.',"").lowercase(java.util.Locale.ROOT))?:"application/octet-stream";pending=D.createDocument(resolver,parent,mime,".dropship-pending-${state.spec.transferId}")?:throw IOException("Cannot create pending file");info.put("pending",pending.toString());V2Json.write(journal,info.toString())}
        try{
            resolver.openOutputStream(pending,"wt")?.use{output->payload.inputStream().use{it.copyTo(output,131072)}}?:throw IOException("Cannot write receive folder")
            if(!matches(pending,state.spec.size,state.spec.sha256))throw V2Error(422,"저장된 파일 검증에 실패했습니다.")
            val final=D.renameDocument(resolver,pending,actual)?:throw IOException("선택한 폴더에서 파일 이름 변경을 지원하지 않습니다. 휴대폰 내부 저장소의 폴더를 선택하세요.")
            actual=display(final);info.put("name",actual).put("pending",final.toString());V2Json.write(journal,info.toString())
            if(!matches(final,state.spec.size,state.spec.sha256))throw V2Error(422,"저장된 파일 검증에 실패했습니다.")
            return Receipt(actual,state.spec.size,state.spec.sha256,listOf(parentPath,actual).filter{it.isNotEmpty()}.joinToString("/"))
        }catch(e:Exception){throw e} // retain hidden pending output for retry; committed outputs are resolved by journal.
    }
}
