package dev.polarzen.chatbox

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary

private interface ToastShell : StdCallLibrary {
    fun SetCurrentProcessExplicitAppUserModelID(appId: WString): Int
}

private interface ToastBridge : Library {
    fun ChatboxShowReplyToast(uri: WString): Int
}

internal object WindowsReplyToast {
    private val bridge by lazy {
        val dll = Native.extractFromResourcePath("/win32-x86-64/chatbox_toast.dll", javaClass.classLoader)
        Native.load(dll.absolutePath, ToastBridge::class.java)
    }

    fun initializeAppId() {
        if (!Platform.isWindows()) return
        try {
            val shell = Native.load("shell32", ToastShell::class.java)
            shell.SetCurrentProcessExplicitAppUserModelID(WString("Polarzen.Chatbox"))
        } catch (_: LinkageError) {
            // Notification support is optional at startup.
        } catch (_: RuntimeException) {
        }
    }

    fun show(uri: String): Boolean {
        if (!Platform.isWindows()) return false
        return try {
            bridge.ChatboxShowReplyToast(WString(uri)) == 0
        } catch (_: LinkageError) {
            false
        } catch (_: Exception) {
            false
        }
    }
}
