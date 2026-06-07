package com.koupper.providers.lsp

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.InputStream
import java.io.OutputStream

/**
 * Low-level JSON-RPC 2.0 framing over a raw byte stream.
 *
 * Wire format:
 *   Content-Length: <N>\r\n
 *   \r\n
 *   <N UTF-8 bytes of JSON>
 *
 * Thread safety: [send] is synchronized on [output]; [receive] must be called from
 * a single reader thread.
 */
internal class LspRpc(
    private val input: InputStream,
    private val output: OutputStream,
    private val mapper: ObjectMapper
) {
    fun send(message: Map<String, Any?>) {
        val json  = mapper.writeValueAsString(message)
        val bytes = json.toByteArray(Charsets.UTF_8)
        val frame = "Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
        synchronized(output) {
            output.write(frame)
            output.write(bytes)
            output.flush()
        }
    }

    /**
     * Blocks until a complete message is available or EOF / error.
     * Returns null on EOF or unrecoverable read failure.
     */
    @Suppress("UNCHECKED_CAST")
    fun receive(): Map<String, Any?>? {
        val headerBytes = readUntilDoubleCrLf() ?: return null
        val header = String(headerBytes, Charsets.US_ASCII)
        val length = CONTENT_LENGTH_RE.find(header)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val body = readExactly(length) ?: return null
        return runCatching { mapper.readValue(body, Map::class.java) as Map<String, Any?> }.getOrNull()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun readUntilDoubleCrLf(): ByteArray? {
        val buf = mutableListOf<Byte>()
        var cr1 = false; var lf1 = false; var cr2 = false
        while (true) {
            val b = input.read()
            if (b == -1) return null
            buf.add(b.toByte())
            when {
                !cr1 && b == '\r'.code  -> cr1 = true
                cr1 && !lf1 && b == '\n'.code -> lf1 = true
                lf1 && !cr2 && b == '\r'.code -> cr2 = true
                cr2 && b == '\n'.code   -> return buf.toByteArray()
                else -> { cr1 = false; lf1 = false; cr2 = false }
            }
        }
    }

    private fun readExactly(n: Int): ByteArray? {
        val buf = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val read = input.read(buf, offset, n - offset)
            if (read == -1) return null
            offset += read
        }
        return buf
    }

    companion object {
        private val CONTENT_LENGTH_RE = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
    }
}
