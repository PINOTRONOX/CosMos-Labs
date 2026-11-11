import com.cosrai.dropship.*
import java.io.*
import java.security.MessageDigest

// org.json adapter is shared in behavior with Android's platform JSONObject.
private class TestDecoder : ResponseDecoder {
    override fun health(json: String): Boolean { val j = org.json.JSONObject(json); return j.optString("service") == "DropShip" && j.optInt("protocol") == 1 }
    override fun receipt(json: String): SendReceipt { val j = org.json.JSONObject(json); return SendReceipt(j.getString("name"), j.getLong("size"), j.getString("sha256"), j.getString("path")) }
    override fun error(json: String) = org.json.JSONObject(json).optString("error", "PC에서 파일을 받지 못했습니다.")
}
fun main(args: Array<String>) {
    val fixtures = File(args[0]); val received = File(args[1]); val ep = DeviceEndpoint.parse("127.0.0.1", "18767")
    val sender = TransferSender(TestDecoder()); val events = object : SendEvents { override fun progress(stage: String, bytes: Long, total: Long) {} }
    var passed = 0
    fun pass(name: String) { passed++; println("PASS $name") }
    sender.check(ep, SendControl()); pass("Kotlin -> C# health")
    fixtures.listFiles()!!.filter { it.name.startsWith("0") }.sortedBy { it.name }.forEach { f ->
        val r = sender.send(ep, f, f.name, events, SendControl())
        val dest = File(received, r.name)
        check(f.length() == r.size && f.readBytes().contentEquals(dest.readBytes()))
        val digest = MessageDigest.getInstance("SHA-256").digest(dest.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
        check(digest == r.sha256); pass("${f.name} ${r.size} bytes stored equality / hash")
    }
    val txt = File(fixtures, "01_test.txt")
    check(sender.send(ep, txt, txt.name, events, SendControl()).name == "01_test (1).txt"); pass("duplicate (1) preserves original")
    check(sender.send(ep, txt, txt.name, events, SendControl()).name == "01_test (2).txt"); pass("duplicate (2)")
    val unicode = File(fixtures, "한글 파일 + 공백.txt").apply { writeText("라이의 DropShip ✓") }
    val r = sender.send(ep, unicode, unicode.name, events, SendControl()); check(r.name == unicode.name && File(received, r.name).readText() == unicode.readText()); pass("Korean spaces plus filename")
    val empty = File(fixtures, "empty.bin").apply { writeBytes(byteArrayOf()) }
    check(sender.send(ep, empty, empty.name, events, SendControl()).size == 0L); pass("zero-byte file")
    for (name in listOf("CON.txt", "LPT1.bin", "../escape.txt", "test.txt ")) {
        try { sender.send(ep, txt, name, events, SendControl()); error("invalid name accepted") } catch (_: IOException) { pass("invalid Windows filename rejected $name") }
    }
    for ((ip, port) in listOf("bad" to "8765", "8.8.8.8" to "8765", "192.168.1.1" to "0", "192.168.1.1" to "65536")) {
        try { DeviceEndpoint.parse(ip, port); error("invalid endpoint accepted") } catch (_: IOException) { pass("invalid/local-only endpoint $ip:$port") }
    }
    try { sender.check(DeviceEndpoint.parse("127.0.0.1", "18768"), SendControl()); error("offline accepted") } catch (_: IOException) { pass("offline PC") }
    val cancelled = SendControl().apply { cancel() }
    try { sender.send(ep, txt, txt.name, events, cancelled); error("cancel accepted") } catch (_: InterruptedIOException) { pass("user cancellation") }
    val midFile=File(fixtures,"mid-cancel-kotlin.bin");File(fixtures,"04_test.zip").copyTo(midFile,true)
    val mid=SendControl();val midEvents=object:SendEvents{override fun progress(stage:String,bytes:Long,total:Long){if(stage=="전송 중"&&bytes>0)mid.cancel()}}
    try{sender.send(ep,midFile,midFile.name,midEvents,mid);error("mid cancellation accepted")}catch(_:InterruptedIOException){pass("mid-upload Kotlin cancellation closes socket")}
    Thread.sleep(250);check(!File(received,midFile.name).exists());check(received.listFiles()!!.none{it.name.startsWith(".dropship-partial-")});pass("Kotlin cancellation leaves no published or partial file");midFile.delete()
    println("TOTAL $passed passed")
}
