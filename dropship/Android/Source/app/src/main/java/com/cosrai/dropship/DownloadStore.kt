package com.cosrai.dropship

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.*

/** Android 10+ scoped storage; no broad storage permission or direct shared-path writes. */
class DownloadStore(private val context: Context) : ReceiveStore {
    private val resolver = context.contentResolver
    private val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val folder = Environment.DIRECTORY_DOWNLOADS + "/DropShip/"
    // Called before accepting connections; owned pending rows from an interrupted run are disposable.
    fun cleanPending() {
        resolver.query(MediaStore.setIncludePending(collection), arrayOf(MediaStore.Downloads._ID), "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.IS_PENDING}=1", arrayOf(folder), null)?.use {
            while (it.moveToNext()) {
                val uri = android.content.ContentUris.withAppendedId(collection, it.getLong(0))
                try { resolver.delete(uri, null, null) } catch (_: Exception) { }
            }
        }
    }
    override fun begin(name: String, size: Long): PendingFile {
        val available = StatFs(Environment.getExternalStorageDirectory().absolutePath).availableBytes
        if (size > available - 4L * 1024 * 1024) throw ReceiveFailure(507, "스마트폰 저장 공간이 부족합니다.")
        val names = mutableSetOf<String>()
        resolver.query(collection, arrayOf(MediaStore.Downloads.DISPLAY_NAME), "${MediaStore.Downloads.RELATIVE_PATH}=?", arrayOf(folder), null)?.use {
            while (it.moveToNext()) names.add(it.getString(0))
        }
        var index = 0
        var unique = name
        while (names.contains(unique)) { index++; unique = ReceiverServer.collisionName(name, index) }
        val extension = unique.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, unique)
            put(MediaStore.Downloads.MIME_TYPE, MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, folder)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        // Provider allocates a NEW unique path even for files this app cannot query.
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")
        try {
            val output = resolver.openOutputStream(uri, "w") ?: throw IOException("MediaStore open failed")
            return DownloadPending(uri, output)
        } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
    }
    private inner class DownloadPending(private val uri: Uri, override val output: OutputStream) : PendingFile {
        private var committed = false
        override fun commit(size: Long, sha256: String): Receipt {
            var actual = ""
            val savedSize = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            // The provider may adjust the name to preserve files owned by other apps.
            resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) actual = it.getString(0)
            }
            if (actual.isEmpty() || savedSize != size) throw ReceiveFailure(422, "저장된 파일 크기가 일치하지 않습니다.")
            // Verify stored bytes too, not just network bytes.
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(131072)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            } ?: throw IOException("Cannot verify stored file")
            val savedHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            if (savedHash != sha256) throw ReceiveFailure(422, "저장된 파일 검증에 실패했습니다.")
            if (resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) != 1)
                throw IOException("Cannot publish file")
            committed = true
            return Receipt(actual, size, savedHash, "Download/DropShip/$actual")
        }
        override fun abort() {
            try { output.close() } catch (_: Exception) { }
            if (!committed) resolver.delete(uri, null, null)
        }
    }
}
