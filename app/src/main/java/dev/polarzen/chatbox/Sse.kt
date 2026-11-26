package dev.polarzen.chatbox

import java.io.BufferedReader

/** Decode complete SSE events. Publish pending UI updates before waiting on a new chunk. */
internal fun readSse(reader: BufferedReader, beforeWait: () -> Unit = {}, emit: (String) -> Boolean) {
    val data = mutableListOf<String>()
    val line = StringBuilder()
    val chunk = CharArray(8192)
    var skipLf = false
    while (true) {
        if (!reader.ready()) beforeWait()
        val size = reader.read(chunk)
        if (size < 0) return
        for (i in 0 until size) {
            val ch = chunk[i]
            val followsCr = skipLf
            skipLf = ch == '\r'
            if (ch == '\n' && followsCr) continue
            if (ch != '\r' && ch != '\n') {
                line.append(ch)
                continue
            }
            if (line.isEmpty()) {
                if (data.isNotEmpty() && !emit(data.joinToString("\n"))) return
                data.clear()
            } else if (line.contentEquals("data") || line.startsWith("data:")) {
                data += line.toString().substringAfter(':', "").removePrefix(" ")
            }
            line.setLength(0)
        }
    }
}
