import com.cosrai.dropship.*
import java.net.InetAddress
fun main(args:Array<String>){
val id="22222222222222222222222222222222"
when(args[0]){
"respond"->{DiscoveryResponder(id,{"라이 폰 + Wing"},"android",18765,18775).use{println("READY");Thread.sleep(Long.MAX_VALUE)}}
"scan"->{val found=DiscoveryScanner.scan(id,targets=listOf(InetAddress.getByName("127.0.0.1")),port=18776);check(found.size==1&&found[0].name=="라이 PC + 책상"&&found[0].endpoint.port==18767);println("PASS Kotlin discovers C# name/id/port/source address")}
else->{val nonce="a".repeat(32);val source=InetAddress.getByName("127.0.0.1");val bytes=DiscoveryWire.offer(nonce,id,"라이 폰 + Wing","android",8765).toByteArray();check(DiscoveryWire.parse(bytes,source,nonce,"other")?.name=="라이 폰 + Wing");println("PASS Kotlin unicode name roundtrip");check(DiscoveryWire.parse(bytes,source,"wrong","other")==null);check(DiscoveryWire.parse(bytes,source,nonce,id)==null);check(DiscoveryWire.parse(bytes,InetAddress.getByName("8.8.8.8"),nonce,"other")==null);check(DiscoveryWire.parse(ByteArray(1025),source,nonce,"other")==null);check(DiscoveryWire.parse("DROPSHIP_OFFER|1|bad".toByteArray(),source,nonce,"other")==null);println("PASS Kotlin ignores stale nonce / self / public IP / oversized / malformed offers");val old=DiscoveryWire.parse(bytes,InetAddress.getByName("192.168.1.2"),nonce,"other")!!;val new=DiscoveryWire.parse(bytes,InetAddress.getByName("192.168.1.9"),nonce,"other")!!;check(old.id==new.id&&old.endpoint.ip!=new.endpoint.ip);println("PASS Kotlin stable device ID resolves changed address");check(DiscoveryWire.queryNonce("DROPSHIP_DISCOVER|1|$nonce")==nonce&&DiscoveryWire.queryNonce("DROPSHIP_DISCOVER|2|$nonce")==null);println("PASS Kotlin discovery query validation")}
}}
