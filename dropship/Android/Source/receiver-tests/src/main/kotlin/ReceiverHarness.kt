import com.cosrai.dropship.*
import java.io.*
import java.nio.file.*
import java.security.MessageDigest

/** Real receiver protocol implementation with a test storage adapter (not Android MediaStore). */
fun main(args: Array<String>) {
    val root = File(args.getOrElse(0) { "received" }).apply { mkdirs() }
    val log = File(root, "events.log")
    val events = object : ReceiveEvents {
        override fun progress(name: String, bytes: Long, total: Long) { if(name.startsWith("mid-cancel-")&&bytes==0L)Thread.sleep(500) }
        override fun completed(receipt: Receipt) { log.appendText("SAVED ${receipt.name} ${receipt.size} ${receipt.sha256}\n") }
        override fun failed(message: String) { log.appendText("FAILED $message\n") }
    }
    val store = object : ReceiveStore {
        override fun begin(name: String, size: Long): PendingFile {
            if (name == "simulate-no-space.bin") throw ReceiveFailure(507, "스마트폰 저장 공간이 부족합니다.")
            if (name == "simulate-write-failure.bin") throw IOException("simulated disk write failure")
            val temp = File.createTempFile(".partial-", "", root)
            return object : PendingFile {
                override val output = FileOutputStream(temp)
                override fun commit(size: Long, sha256: String): Receipt {
                    check(temp.length() == size)
                    val stored = MessageDigest.getInstance("SHA-256").digest(temp.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
                    check(stored == sha256)
                    var i = 0; var dest = File(root, name)
                    while (dest.exists()) { i++; dest = File(root, ReceiverServer.collisionName(name, i)) }
                    Files.move(temp.toPath(), dest.toPath())
                    return Receipt(dest.name, size, stored, "Download/DropShip/${dest.name}")
                }
                override fun abort() { try { output.close() } catch (_: Exception) { }; temp.delete() }
            }
        }
    }
    val receiver = ReceiverServer(store, events, 18765)
    receiver.start()
    Runtime.getRuntime().addShutdownHook(Thread { receiver.close() })
    println("READY 18765 ${root.absolutePath}")
}
