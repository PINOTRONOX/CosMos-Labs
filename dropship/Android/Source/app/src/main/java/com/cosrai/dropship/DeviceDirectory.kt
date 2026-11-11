package com.cosrai.dropship

import java.io.File
import org.json.JSONObject

data class DeviceNote(val alias:String="",val address:String="",val port:Int=8765)
class DeviceDirectory(private val file:File) {
    private var notes=try{JSONObject(file.readText())}catch(_:Exception){JSONObject()}
    @Synchronized fun get(id:String):DeviceNote {val n=notes.optJSONObject(id)?:JSONObject();return DeviceNote(n.optString("alias"),n.optString("address"),n.optInt("port",8765))}
    @Synchronized fun display(id:String,model:String):String {val alias=get(id).alias;return if(alias.isEmpty())model else "$model ($alias)"}
    @Synchronized fun setAlias(id:String,value:String){require(V2Json.hex(id,32));val alias=value.trim().let{if(it.isEmpty())"" else DiscoveryWire.name(it)};persist(id,get(id).copy(alias=alias))}
    @Synchronized fun seen(id:String,endpoint:DeviceEndpoint){require(V2Json.hex(id,32));persist(id,get(id).copy(address=endpoint.ip,port=endpoint.port))}
    @Synchronized fun hint(id:String):DeviceEndpoint?=try{get(id).let{DeviceEndpoint.parse(it.address,it.port.toString())}}catch(_:Exception){null}
    @Synchronized fun remove(id:String){val next=JSONObject(notes.toString());next.remove(id);V2Json.write(file,next.toString());notes=next}
    private fun persist(id:String,n:DeviceNote){val next=JSONObject(notes.toString());next.put(id,JSONObject().put("alias",n.alias).put("address",n.address).put("port",n.port));V2Json.write(file,next.toString());notes=next}
}
data class RemoteInfo(val id:String,val name:String,val receiveReady:Boolean,val receiveMessage:String)
