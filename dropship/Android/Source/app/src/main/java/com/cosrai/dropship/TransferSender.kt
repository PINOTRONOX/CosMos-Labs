package com.cosrai.dropship

import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class DeviceEndpoint(val ip: String, val port: Int) {
    companion object {
        fun parse(ip: String, port: String): DeviceEndpoint {
            val parts = ip.trim().split('.')
            val numbers = parts.map { it.toIntOrNull() ?: -1 }
            if (numbers.size != 4 || numbers.any { it !in 0..255 } || parts.any { it.isEmpty() || it.any { c -> c !in '0'..'9' } })
                throw IOException("PC 화면의 IPv4 주소를 입력해 주세요. 예: 192.168.0.25")
            val b = numbers
            if (!(b[0] == 10 || b[0] == 192 && b[1] == 168 || b[0] == 172 && b[1] in 16..31 || b[0] == 169 && b[1] == 254 || b[0] == 127))
                throw IOException("같은 Wi-Fi의 로컬 IP 주소만 사용할 수 있습니다.")
            val p = port.trim().toIntOrNull()
            if (p == null || p !in 1..65535) throw IOException("Port는 1~65535 사이 숫자로 입력해 주세요.")
            return DeviceEndpoint(numbers.joinToString("."), p)
        }
    }
}
class SendControl {
    val cancelled = AtomicBoolean(false)
    @Volatile private var socket: Socket? = null
    fun attach(value: Socket) {
        socket = value
        if (cancelled.get()) { value.close(); check() }
    }
    fun cancel() { cancelled.set(true); try { socket?.close() } catch (_: Exception) {} }
    fun check() { if (cancelled.get()) throw InterruptedIOException("전송을 취소했습니다. PC에서 완료 여부를 확인해 주세요.") }
}
interface SendEvents { fun progress(stage: String, bytes: Long, total: Long) }
data class SendReceipt(val name: String, val size: Long, val sha256: String, val path: String)

/** JSON decoder injected by Android/JVM adapter; transport has no Android dependency. */
interface ResponseDecoder {
    fun health(json: String): Boolean
    fun receipt(json: String): SendReceipt
    fun error(json: String): String
}
class TransferSender(private val decoder: ResponseDecoder) {
    fun check(endpoint: DeviceEndpoint, control: SendControl) {
        Socket().use { socket ->
            control.attach(socket); control.check()
            socket.connect(InetSocketAddress(endpoint.ip, endpoint.port), 5000); socket.soTimeout = 7000
            socket.getOutputStream().write(("GET /v1/health HTTP/1.1\r\nHost: ${endpoint.ip}:${endpoint.port}\r\nConnection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII))
            val (code, json) = response(socket.getInputStream())
            if (code != 200 || !decoder.health(json)) throw IOException("해당 주소에 호환되는 DropShip이 없습니다.")
        }
    }
    fun send(endpoint: DeviceEndpoint, file: File, name: String, events: SendEvents, control: SendControl): SendReceipt {
        ReceiverServer.validateName(name)
        check(endpoint, control)
        val hash = MessageDigest.getInstance("SHA-256")
        val total = file.length()
        val buffer = ByteArray(131072)
        events.progress("파일 확인 중", 0, total)
        FileInputStream(file).use { input ->
            var bytes = 0L; var last = System.nanoTime()
            while (true) {
                control.check(); val n = input.read(buffer); if (n < 0) break
                hash.update(buffer, 0, n); bytes += n
                if (System.nanoTime() - last > 150_000_000) { events.progress("파일 확인 중", bytes, total); last = System.nanoTime() }
            }
            if (bytes != total) throw IOException("선택한 파일이 바뀌었습니다. 다시 선택해 주세요.")
        }
        val digest = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        Socket().use { socket ->
            control.attach(socket); control.check()
            socket.connect(InetSocketAddress(endpoint.ip, endpoint.port), 5000); socket.soTimeout = 60000
            val nameEncoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            val header = "POST /v1/files HTTP/1.1\r\nHost: ${endpoint.ip}:${endpoint.port}\r\nContent-Length: $total\r\nX-DropShip-Filename: $nameEncoded\r\nX-DropShip-SHA256: $digest\r\nConnection: close\r\n\r\n"
            val lastIO = java.util.concurrent.atomic.AtomicLong(System.nanoTime())
            val stalled = AtomicBoolean(false)
            val watchdog = Executors.newSingleThreadScheduledExecutor()
            watchdog.scheduleWithFixedDelay({
                if (System.nanoTime() - lastIO.get() > 60_000_000_000L) { stalled.set(true); try { socket.close() } catch (_: Exception) {} }
            }, 5, 5, TimeUnit.SECONDS)
            try {
                val output = BufferedOutputStream(socket.getOutputStream(), 65536)
                output.write(header.toByteArray(StandardCharsets.US_ASCII)); output.flush()
                FileInputStream(file).use { input ->
                    var sent = 0L; var last = System.nanoTime()
                    while (sent < total) {
                        control.check()
                        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), total - sent).toInt())
                        if (n < 0) throw EOFException("파일을 읽지 못했습니다.")
                        output.write(buffer, 0, n); sent += n; lastIO.set(System.nanoTime())
                        if (System.nanoTime() - last > 100_000_000 || sent == total) {
                            events.progress("전송 중", sent, total); last = System.nanoTime()
                        }
                    }
                }
                output.flush(); lastIO.set(System.nanoTime())
                events.progress("PC 저장·검증 대기 중", total, total)
                val (code, json) = response(socket.getInputStream())
                if (code !in 200..299) throw IOException(decoder.error(json))
                val receipt = decoder.receipt(json)
                if (receipt.size != total || !receipt.sha256.equals(digest, true))
                    throw IOException("수신 파일의 검증 결과가 일치하지 않습니다.")
                return receipt
            } catch (e: IOException) {
                control.check()
                if (stalled.get()) throw SocketTimeoutException("PC 응답 시간이 초과되었습니다.")
                throw e
            } finally { watchdog.shutdownNow() }
        }
    }
    private fun response(raw: InputStream): Pair<Int, String> {
        val input = BufferedInputStream(raw)
        val status = line(input).split(' ')
        if (status.size < 2 || !status[0].startsWith("HTTP/1.")) throw IOException("PC 응답 형식이 올바르지 않습니다.")
        val code = status[1].toIntOrNull() ?: throw IOException("PC 응답을 확인할 수 없습니다.")
        var length = -1; var count = 0; var bytes = 0
        while (true) {
            val h = line(input); bytes += h.length
            if (++count > 40 || bytes > 16384) throw IOException("PC 응답 정보가 너무 큽니다.")
            if (h.isEmpty()) break
            val at = h.indexOf(':'); if (at <= 0) throw IOException("PC 응답 형식이 올바르지 않습니다.")
            if (h.substring(0, at).equals("Content-Length", true)) {
                if (length != -1) throw IOException("중복된 PC 응답 정보입니다.")
                length = h.substring(at + 1).trim().toIntOrNull() ?: -1
            }
        }
        if (length !in 0..16384) throw IOException("PC 응답 크기가 올바르지 않습니다.")
        val body = ByteArray(length); var read = 0
        while (read < length) { val n = input.read(body, read, length - read); if (n < 0) throw EOFException("PC와 연결이 끊어졌습니다."); read += n }
        return code to String(body, StandardCharsets.UTF_8)
    }
    private fun line(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (out.size() <= 4096) {
            val c = input.read(); if (c < 0) throw EOFException("PC와 연결이 끊어졌습니다.")
            if (c == 10) {
                val b = out.toByteArray()
                if (b.isEmpty() || b.last() != 13.toByte()) throw IOException("PC 응답 형식이 올바르지 않습니다.")
                return String(b, 0, b.size - 1, StandardCharsets.US_ASCII)
            }
            out.write(c)
        }
        throw IOException("PC 응답 정보가 너무 큽니다.")
    }
}
