package dev.localllm

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** SAF streams need not be seekable. Import once and use the upstream mmap-capable path loader. */
internal class ModelStore(private val context: Context) {
    suspend fun prepare(uri: Uri, reimport: Boolean): File = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "models").apply { mkdirs() }
        val key = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        val target = File(directory, "$key.gguf")
        if (!reimport && target.isFile && target.length() > 0) return@withContext target
        val temporary = File.createTempFile("import-", ".part", directory)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open GGUF")
            input.use { source -> temporary.outputStream().use { output ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            } }
            check(temporary.length() > 0) { "GGUF is empty" }
            check(temporary.renameTo(target)) { "Could not install GGUF; check free storage" }
            target
        } finally { temporary.delete() }
    }
    suspend fun retainOnly(file: File) = withContext(Dispatchers.IO) {
        file.parentFile?.listFiles()?.filter { it != file && it.extension == "gguf" }?.forEach { it.delete() }
    }
}
