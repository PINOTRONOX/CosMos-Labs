package com.cosrai.dropship

import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Storage is separated from transport to support future pairing and other destinations. */
interface ReceiveStore {
    fun begin(name: String, size: Long): PendingFile
}
interface PendingFile {
    val output: OutputStream
    fun commit(size: Long, sha256: String): Receipt
    fun abort()
}
data class Receipt(val name: String, val size: Long, val sha256: String, val path: String)
class ReceiveFailure(val status: Int, override val message: String) : IOException(message)
interface ReceiveEvents {
    fun progress(name: String, bytes: Long, total: Long)
    fun completed(receipt: Receipt)
    fun failed(message: String)
}

/** No libraries, no external endpoint, bounded memory, one active upload. */
class ReceiverServer(private val store: ReceiveStore, private val events: ReceiveEvents, private val port: Int = 8765) : Closeable {
    private val running = AtomicBoolean(false)
    private val uploading = AtomicBoolean(false)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8))
    private var server: ServerSocket? = null
    fun start() {
        val listener = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress("0.0.0.0", port), 8) }
        server = listener
        running.set(true)
        Thread({
            while (running.get()) {
                try {
                    val socket = listener.accept()
                    socket.soTimeout = 30000
                    clients.add(socket)
                    try { workers.execute { handle(socket) } }
                    catch (_: RejectedExecutionException) { clients.remove(socket); socket.close() }
                } catch (_: IOException) { if (running.get()) events.failed("수신 연결을 열지 못했습니다. 앱을 다시 실행해 주세요.") }
            }
        }, "DropShip-listener").start()
    }
    private fun handle(socket: Socket) {
        var pending: PendingFile? = null
        var ownsUpload = false
        var committed = false
        try {
            val input = BufferedInputStream(socket.getInputStream(), 65536)
            val request = line(input).split(' ')
            if (request.size != 3 || request[2] != "HTTP/1.1") throw ReceiveFailure(400, "지원하지 않는 요청입니다.")
            val headers = mutableMapOf<String, String>()
            var headerBytes = 0
            while (true) {
                val h = line(input)
                headerBytes += h.length
                if (headerBytes > 16384 || headers.size > 40) throw ReceiveFailure(431, "요청 정보가 너무 큽니다.")
                if (h.isEmpty()) break
                val at = h.indexOf(':')
                if (at <= 0) throw ReceiveFailure(400, "요청 형식이 올바르지 않습니다.")
                val key = h.substring(0, at).lowercase(java.util.Locale.ROOT)
                if (headers.put(key, h.substring(at + 1).trim()) != null) throw ReceiveFailure(400, "중복된 요청 정보입니다.")
            }
            if (request[0] == "GET" && request[1] == "/v1/health") {
                respond(socket, 200, "{\"service\":\"DropShip\",\"protocol\":1,\"version\":\"0.4.0\",\"port\":$port}")
                return
            }
            if (request[0] != "POST" || request[1] != "/v1/files") throw ReceiveFailure(404, "지원하지 않는 전송 경로입니다.")
            if (headers.containsKey("transfer-encoding")) throw ReceiveFailure(400, "파일 크기가 포함된 전송만 지원합니다.")
            val length = headers["content-length"]?.toLongOrNull() ?: throw ReceiveFailure(411, "파일 크기를 확인할 수 없습니다.")
            if (length < 0) throw ReceiveFailure(400, "파일 크기가 올바르지 않습니다.")
            val rawName = try { URLDecoder.decode(headers["x-dropship-filename"] ?: "", "UTF-8") }
                catch (_: IllegalArgumentException) { throw ReceiveFailure(400, "파일명이 올바르지 않습니다.") }
            val name = validateName(rawName)
            val expected = headers["x-dropship-sha256"]?.lowercase(java.util.Locale.ROOT) ?: ""
            if (!expected.matches(Regex("[0-9a-f]{64}"))) throw ReceiveFailure(400, "SHA-256 정보가 올바르지 않습니다.")
            if (!uploading.compareAndSet(false, true)) throw ReceiveFailure(409, "다른 파일을 받는 중입니다. 완료 후 다시 시도해 주세요.")
            ownsUpload = true
            pending = store.begin(name, length)
            events.progress(name, 0, length)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(131072)
            var received = 0L
            var last = System.nanoTime()
            while (received < length) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), length - received).toInt())
                if (n < 0) throw EOFException("incomplete upload")
                pending.output.write(buffer, 0, n)
                digest.update(buffer, 0, n)
                received += n
                if (System.nanoTime() - last > 150_000_000 || received == length) {
                    events.progress(name, received, length); last = System.nanoTime()
                }
            }
            pending.output.close()
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            if (hash != expected) throw ReceiveFailure(422, "파일 검증에 실패했습니다. 다시 전송해 주세요.")
            if (!running.get()) throw IOException("receiver stopped")
            val receipt = pending.commit(received, hash)
            committed = true
            events.completed(receipt)
            respond(socket, 201, "{\"name\":${quote(receipt.name)},\"size\":${receipt.size},\"sha256\":${quote(receipt.sha256)},\"path\":${quote(receipt.path)}}")
        } catch (e: Exception) {
            val code = if (e is ReceiveFailure) e.status else if (isNoSpace(e)) 507 else 500
            val message = when {
                committed -> "파일은 저장됐지만 PC에 결과를 전달하지 못했습니다."
                e is ReceiveFailure -> e.message
                isNoSpace(e) -> "스마트폰 저장 공간이 부족합니다."
                e is SocketTimeoutException -> "파일 전송이 멈춰 수신을 중단했습니다. 다시 시도해 주세요."
                e is EOFException || e is SocketException -> "파일 전송 중 연결이 끊어졌습니다."
                else -> "파일을 저장하지 못했습니다. 저장 공간과 연결 상태를 확인해 주세요."
            }
            if (ownsUpload) events.failed(message)
            try { respond(socket, code, "{\"error\":${quote(message)}}") } catch (_: Exception) { }
        } finally {
            if (!committed) try { pending?.abort() } catch (_: Exception) { }
            if (ownsUpload) uploading.set(false)
            clients.remove(socket)
            try { socket.close() } catch (_: Exception) { }
        }
    }
    override fun close() {
        running.set(false)
        try { server?.close() } catch (_: Exception) { }
        clients.forEach { try { it.close() } catch (_: Exception) { } }
        // Queued work still runs on closed sockets so its cleanup always executes.
        workers.shutdown()
    }
    companion object {
        fun validateName(name: String): String {
            val stem = name.substringBefore('.').trimEnd(' ').uppercase(java.util.Locale.ROOT)
            if (name.isBlank() || name.endsWith('.') || name.endsWith(' ') || name.toByteArray(StandardCharsets.UTF_8).size > 220 ||
                stem in setOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$") || Regex("^(COM|LPT)[1-9¹²³]$").matches(stem) ||
                name.any { it < ' ' || it == '\u007f' || it in "/\\:*?\"<>|" })
                throw ReceiveFailure(400, "지원하지 않는 파일명입니다. 파일명을 바꾸고 다시 시도해 주세요.")
            return name
        }
        fun collisionName(name: String, index: Int): String {
            if (index == 0) return name
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            return name.substring(0, dot) + " ($index)" + name.substring(dot)
        }
        private fun line(input: InputStream): String {
            val out = ByteArrayOutputStream()
            while (out.size() <= 4096) {
                val c = input.read()
                if (c < 0) throw EOFException()
                if (c == 10) {
                    val b = out.toByteArray()
                    if (b.isEmpty() || b.last() != 13.toByte()) throw ReceiveFailure(400, "요청 형식이 올바르지 않습니다.")
                    return String(b, 0, b.size - 1, StandardCharsets.US_ASCII)
                }
                out.write(c)
            }
            throw ReceiveFailure(431, "요청 정보가 너무 큽니다.")
        }
        fun quote(value: String): String = buildString {
            append('"'); value.forEach { c -> when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            } }; append('"')
        }
        private fun isNoSpace(error: Throwable): Boolean {
            var e: Throwable? = error
            while (e != null) { if (e.message?.contains("ENOSPC", true) == true || e.message?.contains("No space", true) == true) return true; e = e.cause }
            return false
        }
        private fun respond(socket: Socket, status: Int, json: String) {
            val body = json.toByteArray(StandardCharsets.UTF_8)
            val reason = if (status < 300) "OK" else "Error"
            val out = socket.getOutputStream()
            out.write("HTTP/1.1 $status $reason\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            out.write(body); out.flush()
        }
    }
}
