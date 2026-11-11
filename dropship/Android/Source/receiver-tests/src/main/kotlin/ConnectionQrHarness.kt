import com.cosrai.dropship.ConnectionQr
fun main(){
 val now=2000000000L
 val q="dropship://connect?v=1&ip=192.168.0.8&port=8765&id="+"a".repeat(32)+"&exp="+(now+600)
 var count=0
 fun good(value:String){check(ConnectionQr.parse(value,now).endpoint.port==8765);count++}
 fun bad(value:String){check(runCatching{ConnectionQr.parse(value,now)}.isFailure){value};count++}
 good(q);good(q.replace("192.168.0.8","10.0.0.2"));bad(q.replace("dropship:","https:"));bad(q.replace("connect?","evil?"));bad(q.replace("v=1","v=2"));bad(q+"&ip=10.0.0.3");bad(q.replace("192.168.0.8","8.8.8.8"));bad(q.replace("192.168.0.8","127.0.0.1"));bad(q.replace("8765","0"));bad(q.replace("a".repeat(32),"wrong"));bad(q.replace((now+600).toString(),now.toString()));bad(q.replace((now+600).toString(),(now+700).toString()));bad(q+"#x");bad(q.replace("&exp=","&other="));bad("https://example.com");bad("x".repeat(513));println("PASS $count QR validation cases")
}
