package dev.polarzen.chatbox

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.StdCallLibrary
import java.awt.EventQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal data class DesktopHotkey(val modifiers: Int, val virtualKey: Int) {
    override fun toString() = buildList {
        for ((name, bit) in modifierNames) if (modifiers and bit != 0) add(name)
        add(if (virtualKey in 0x70..0x87) "F${virtualKey - 0x6f}" else virtualKey.toChar().toString())
    }.joinToString("+")

    companion object {
        private val modifierNames = mapOf("Ctrl" to 2, "Alt" to 1, "Shift" to 4, "Win" to 8)
        val DEFAULT = DesktopHotkey(3, 'J'.code)

        fun parse(text: String): DesktopHotkey? {
            val parts = text.split('+').map { it.trim() }
            var modifiers = 0
            for (name in parts.dropLast(1)) {
                val bit = modifierNames.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: return null
                if (modifiers and bit != 0) return null
                modifiers = modifiers or bit
            }
            if (modifiers == 0) return null
            val key = parts.last()
            val virtualKey = when {
                key.length == 1 && (key[0] in '0'..'9' || key[0] in 'A'..'Z' || key[0] in 'a'..'z') -> key[0].uppercaseChar().code
                key.startsWith("F", ignoreCase = true) -> {
                    val number = key.drop(1).toIntOrNull() ?: return null
                    if (number !in 1..24 || number == 12 || key.drop(1) != number.toString()) return null
                    0x6f + number
                }
                else -> return null
            }
            return DesktopHotkey(modifiers, virtualKey)
        }
    }
}

@Structure.FieldOrder("hwnd", "message", "wParam", "lParam", "time", "x", "y", "reserved")
internal class HotkeyMessage : Structure() {
    @JvmField var hwnd: Pointer? = null
    @JvmField var message = 0
    @JvmField var wParam: Pointer? = null
    @JvmField var lParam: Pointer? = null
    @JvmField var time = 0
    @JvmField var x = 0
    @JvmField var y = 0
    @JvmField var reserved = 0
}

private interface HotkeyUser32 : StdCallLibrary {
    fun RegisterHotKey(window: Pointer?, id: Int, modifiers: Int, key: Int): Int
    fun UnregisterHotKey(window: Pointer?, id: Int): Int
    fun PeekMessageW(msg: HotkeyMessage, window: Pointer?, min: Int, max: Int, flags: Int): Int
    fun GetMessageW(msg: HotkeyMessage, window: Pointer?, min: Int, max: Int): Int
    fun PostThreadMessageW(thread: Int, message: Int, wParam: Pointer?, lParam: Pointer?): Int
}

private interface HotkeyKernel32 : StdCallLibrary {
    fun GetCurrentThreadId(): Int
}

private const val COMMAND = 0x8001
private const val WM_HOTKEY = 0x0312
private const val MOD_NOREPEAT = 0x4000
private const val UNAVAILABLE = "全局快捷键服务不可用"

internal class WindowsHotkeys(private val onPressed: () -> Unit) : AutoCloseable {
    private data class Registration(val id: Int, val key: DesktopHotkey)
    private class Change(val next: DesktopHotkey?) {
        val result = CompletableFuture<String?>()
    }

    private val api by lazy { Native.load("user32", HotkeyUser32::class.java) }
    private val ready = CompletableFuture<Int>()
    private val stopped = CompletableFuture<String?>()
    @Volatile private var closed = false
    @Volatile private var pending: Change? = null
    @Volatile private var registration: Registration? = null
    private val worker = if (Platform.isWindows()) {
        Thread(::runLoop, "ChatBox Windows hotkeys").apply { isDaemon = true; start() }
    } else null

    @Synchronized
    fun change(next: DesktopHotkey?): String? {
        if (closed) return UNAVAILABLE
        if (worker == null) return if (next == null) null else "当前系统不支持 Windows 全局快捷键"
        if (next != null && DesktopHotkey.parse(next.toString()) != next) return "不支持此快捷键"
        val threadId = try {
            ready.get(3, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            return UNAVAILABLE
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return UNAVAILABLE
        }
        if (threadId == 0 || stopped.isDone) return UNAVAILABLE
        val change = Change(next)
        pending = change
        return try {
            if (api.PostThreadMessageW(threadId, COMMAND, null, null) == 0) UNAVAILABLE
            else CompletableFuture.anyOf(change.result, stopped).join() as String?
        } finally {
            pending = null
        }
    }

    private fun runLoop() {
        try {
            val msg = HotkeyMessage()
            api.PeekMessageW(msg, null, 0, 0, 0) // Creates this thread's Win32 message queue.
            ready.complete(Native.load("kernel32", HotkeyKernel32::class.java).GetCurrentThreadId())
            while (!closed && api.GetMessageW(msg, null, 0, 0) > 0) {
                when (msg.message) {
                    COMMAND -> pending?.let { it.result.complete(replace(it.next)) }
                    WM_HOTKEY -> {
                        val active = registration ?: continue
                        if (Pointer.nativeValue(msg.wParam).toInt() == active.id) {
                            EventQueue.invokeLater { if (!closed && registration === active) onPressed() }
                        }
                    }
                }
            }
        } catch (_: LinkageError) {
            // Missing or incompatible native library.
        } finally {
            ready.complete(0)
            try {
                registration?.let { api.UnregisterHotKey(null, it.id) }
            } finally {
                registration = null
                stopped.complete(UNAVAILABLE)
            }
        }
    }

    // Only the message-loop thread registers or unregisters a binding.
    private fun replace(next: DesktopHotkey?): String? {
        val old = registration
        if (next == old?.key) return null
        val spareId = if (old?.id == 1) 2 else 1
        if (next != null && api.RegisterHotKey(null, spareId, next.modifiers or MOD_NOREPEAT, next.virtualKey) == 0) {
            val code = Native.getLastError()
            return if (code == 1409) "快捷键 $next 已被占用" else "无法注册快捷键 $next（Windows 错误 $code）"
        }
        if (old != null && api.UnregisterHotKey(null, old.id) == 0) {
            val code = Native.getLastError()
            if (next != null) api.UnregisterHotKey(null, spareId)
            return "无法释放原快捷键（Windows 错误 $code）"
        }
        registration = next?.let { Registration(spareId, it) }
        return null
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        val threadId = ready.getNow(0)
        if (threadId != 0 && !stopped.isDone) api.PostThreadMessageW(threadId, COMMAND, null, null)
        try {
            worker?.join(3000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
