package dev.polarzen.chatbox

import java.io.BufferedReader

/** Decode SSE events, preserving multi-line data, omitting comments and empty events. */
internal fun readSse(reader: BufferedReader, emit: (String) -> Boolean) {
    val data = mutableListOf<String>()
    while (true) {
        val line = reader.readLine() ?: break
        if (line.isEmpty()) {
            if (data.isNotEmpty() && !emit(data.joinToString("\n"))) return
            data.clear()
        } else if (line == "data" || line.startsWith("data:")) {
            data += line.substringAfter(':', "").removePrefix(" ")
        }
    }
}
