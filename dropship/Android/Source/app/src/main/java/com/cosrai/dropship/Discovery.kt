package com.cosrai.dropship
import java.io.Closeable
import java.net.*
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class Peer(val id:String,val name:String,val kind:String,val endpoint:DeviceEndpoint) { override fun toString()="$name · $kind · ${endpoint.ip}" }
object DiscoveryWire {
    const val PORT=8766
    fun name(value:String):String { val n=value.trim();require(n.isNotEmpty()&&n.length<=40&&n.toByteArray(StandardCharsets.UTF_8).size<=120&&n.none{it<' '||it=='\u007f'}){"기기 이름은 제어문자 없이 1~40자로 입력해 주세요."};return n }
    private fun hex(s:String)=s.matches(Regex("[0-9a-fA-F]{32}"))
    fun queryNonce(query:String):String? { val p=query.split('|');return if(p.size==3&&p[0]=="DROPSHIP_DISCOVER"&&p[1]=="1"&&hex(p[2]))p[2] else null }
    fun offer(nonce:String,id:String,name:String,kind:String,port:Int)="DROPSHIP_OFFER|1|$nonce|$id|${URLEncoder.encode(name(name),"UTF-8").replace("+","%20")}|$kind|$port|0.5.0"
    fun parse(bytes:ByteArray,source:InetAddress,nonce:String,ownId:String):Peer?=try {
        if(bytes.size>1024)null else { val p=String(bytes,StandardCharsets.UTF_8).split('|');if(p.size!=8||p[0]!="DROPSHIP_OFFER"||p[1]!="1"||p[2]!=nonce||!hex(p[3])||p[3]==ownId||p[5] !in listOf("windows","android")||p[7]!="0.5.0")null else Peer(p[3],name(URLDecoder.decode(p[4],"UTF-8")),p[5],DeviceEndpoint.parse(source.hostAddress?:"",p[6])) }
    }catch(_:Exception){null}
}
class DiscoveryResponder(private val id:String,private val name:()->String,private val kind:String,private val tcpPort:Int=8765,udpPort:Int=8766):Closeable {
    private val socket=DatagramSocket(null).apply{bind(InetSocketAddress("0.0.0.0",udpPort))}
    private val running=AtomicBoolean(true)
    private val thread=Thread({
        val buffer=ByteArray(1025)
        while(running.get())try {
            val packet=DatagramPacket(buffer,buffer.size);socket.receive(packet)
            if(packet.length>1024)continue
            val nonce=DiscoveryWire.queryNonce(String(packet.data,0,packet.length,StandardCharsets.US_ASCII))?:continue
            try{DeviceEndpoint.parse(packet.address.hostAddress?:"","1");val bytes=DiscoveryWire.offer(nonce,id,name(),kind,tcpPort).toByteArray(StandardCharsets.UTF_8);socket.send(DatagramPacket(bytes,bytes.size,packet.address,packet.port))}catch(_:Exception){}
        }catch(_:SocketException){if(!running.get())break}catch(_:Exception){}
    },"DropShip-discovery").apply{start()}
    override fun close(){running.set(false);socket.close();thread.join(1000)}
}
object DiscoveryScanner {
    fun broadcasts():List<InetAddress> {val addresses=mutableSetOf(InetAddress.getByName("255.255.255.255"));try{NetworkInterface.getNetworkInterfaces().toList().filter{it.isUp&&!it.isLoopback}.flatMap{it.interfaceAddresses}.mapNotNull{it.broadcast}.forEach{addresses.add(it)}}catch(_:Exception){};return addresses.take(16)}
    fun scan(ownId:String,cancelled:AtomicBoolean=AtomicBoolean(false),targets:List<InetAddress> = broadcasts(),port:Int=8766):List<Peer> {
        val nonce=UUID.randomUUID().toString().replace("-","");val bytes="DROPSHIP_DISCOVER|1|$nonce".toByteArray(StandardCharsets.US_ASCII);val peers=linkedMapOf<String,Peer>()
        DatagramSocket().use{socket ->
            socket.broadcast=true;socket.soTimeout=200;val end=System.nanoTime()+3_000_000_000L;var next=0L;var sent=0;val buffer=ByteArray(1025)
            while(System.nanoTime()<end&&!cancelled.get()){
                if(sent<3&&System.nanoTime()>=next){targets.distinct().take(16).forEach{try{socket.send(DatagramPacket(bytes,bytes.size,it,port))}catch(_:Exception){}};sent++;next=System.nanoTime()+650_000_000}
                try{val p=DatagramPacket(buffer,buffer.size);socket.receive(p);val peer=DiscoveryWire.parse(p.data.copyOf(p.length),p.address,nonce,ownId);if(peer!=null&&peers.size<64)peers.putIfAbsent(peer.id,peer)}catch(_:SocketTimeoutException){}
            }
        };return peers.values.sortedBy{it.name}
    }
}
