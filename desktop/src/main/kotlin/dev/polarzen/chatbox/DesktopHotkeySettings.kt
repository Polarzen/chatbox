package dev.polarzen.chatbox

/** Called serially by the desktop shell. Each binding owns its Win32 message loop. */
internal class DesktopHotkeySettings(private val onPressed: () -> Unit) : AutoCloseable {
    private var owner: WindowsHotkeys? = null
    var registeredKey: DesktopHotkey? = null
        private set

    fun change(next: DesktopHotkey?, persist: () -> Unit = {}): String? {
        val changed = registeredKey != next
        val candidate = if (changed && next != null) WindowsHotkeys(onPressed) else null
        try {
            candidate?.change(next)?.let { return it }
            try {
                persist()
            } catch (_: Exception) {
                return "无法保存桌面设置。"
            }
            if (changed) {
                val previous = owner
                owner = candidate
                registeredKey = next
                previous?.close()
            }
            return null
        } finally {
            // A failed reservation or save leaves the previous binding continuously held.
            if (candidate !== owner) candidate?.close()
        }
    }

    override fun close() {
        owner?.close()
        owner = null
        registeredKey = null
    }
}
