package dev.localllm

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors

data class Msg(val role: String, val content: String)

fun interface TokenSink { fun onToken(bytes: ByteArray): Boolean }

/** Decodes a stream of UTF-8 byte chunks, holding back incomplete trailing characters. */
class Utf8Stream {
    private val dec = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var carry = ByteArray(0)
    fun push(chunk: ByteArray): String {
        val all = carry + chunk
        val inBuf = ByteBuffer.wrap(all)
        val out = CharBuffer.allocate(all.size + 4)
        dec.decode(inBuf, out, false)
        carry = ByteArray(inBuf.remaining()).also { inBuf.get(it) }
        out.flip()
        return out.toString()
    }
}

class LlamaEngine {
    // llama.cpp contexts aren't thread-safe: every native call runs on this one thread.
    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "llama").apply { priority = Thread.MAX_PRIORITY }
    }.asCoroutineDispatcher()

    private external fun nativeLoad(path: String, nCtx: Int, nThreads: Int, backend: String, gpuLayers: Int, cacheDir: String): String
    private external fun nativeBackendInfo(): ByteArray
    private external fun nativeApplyTemplate(roles: Array<String>, contents: Array<ByteArray>): ByteArray
    private external fun nativeGenerate(prompt: ByteArray, maxNew: Int, temp: Float, sink: TokenSink): Int
    private external fun nativeLastError(): String
    private external fun nativeProgress(): Long
    private external fun nativeStats(): String
    private external fun nativeCancel()
    private external fun nativeFree()

    @Volatile private var libraryLoaded = false
    private fun ensureLibrary() {
        if (!libraryLoaded) { System.loadLibrary("llama_jni"); libraryLoaded = true }
    }

    /** Returns "" on success, otherwise the error message from llama.cpp. */
    suspend fun load(path: String, nCtx: Int, settings: GpuSettings, cacheDir: String): String =
        withContext(dispatcher) {
            try {
                ensureLibrary()
                nativeLoad(path, nCtx, performanceCores(), settings.backend.id, settings.gpuLayers, cacheDir)
            } catch (e: UnsatisfiedLinkError) {
                "Native GPU library failed to load: ${e.message}"
            }
        }
    suspend fun backendInfo(): String = withContext(dispatcher) {
        String(nativeBackendInfo(), Charsets.UTF_8)
    }

    suspend fun template(msgs: List<Msg>): String = withContext(dispatcher) {
        String(
            nativeApplyTemplate(
                msgs.map { it.role }.toTypedArray(),
                msgs.map { it.content.toByteArray() }.toTypedArray()
            ), Charsets.UTF_8
        )
    }

    /** Streams text pieces to [onText]; return false from it to stop early. */
    suspend fun generate(prompt: String, maxNew: Int, temp: Float, onText: (String) -> Boolean): Int =
        withContext(dispatcher) {
            val dec = Utf8Stream()
            nativeGenerate(prompt.toByteArray(), maxNew, temp) { bytes -> onText(dec.push(bytes)) }
        }

    suspend fun lastError(): String = withContext(dispatcher) { nativeLastError() }
    /** (tokens done shl 32) or total tokens of the current prompt prefill; safe to poll from any thread. */
    fun progress(): Long = if (libraryLoaded) nativeProgress() else 0L
    fun stats(): String = nativeStats()
    fun cancel() { if (libraryLoaded) nativeCancel() }
    suspend fun free() = withContext(dispatcher) { if (libraryLoaded) nativeFree() }

    /** Threads on the fast cores only; spilling onto LITTLE cores makes decoding slower, not faster. */
    private fun performanceCores(): Int {
        val n = Runtime.getRuntime().availableProcessors()
        val freqs = (0 until n).mapNotNull {
            runCatching { File("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq").readText().trim().toInt() }.getOrNull()
        }
        if (freqs.isEmpty()) return (n / 2).coerceAtLeast(2)
        val max = freqs.max()
        return freqs.count { it >= max * 0.7 }.coerceIn(2, 8)
    }
}
