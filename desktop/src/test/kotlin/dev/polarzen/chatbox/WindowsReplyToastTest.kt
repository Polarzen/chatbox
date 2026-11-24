package dev.polarzen.chatbox

import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class WindowsReplyToastTest {
    @Test
    fun processUsesTheInstalledShortcutIdentity() {
        assumeTrue(Platform.isWindows())
        WindowsReplyToast.initializeAppId()
        val out = PointerByReference()
        val shell = NativeLibrary.getInstance("shell32")
        assertEquals(0, shell.getFunction("GetCurrentProcessExplicitAppUserModelID").invokeInt(arrayOf(out)))
        try {
            assertEquals("Polarzen.Chatbox", out.value.getWideString(0))
        } finally {
            NativeLibrary.getInstance("ole32").getFunction("CoTaskMemFree").invokeVoid(arrayOf(out.value))
        }
    }

    @Test
    fun packagedBridgeRejectsAnUnrelatedProtocolWithoutDisplayingAnything() {
        assumeTrue(Platform.isWindows() && Platform.RESOURCE_PREFIX == "win32-x86-64")
        val dll = Native.extractFromResourcePath("/win32-x86-64/chatbox_toast.dll", javaClass.classLoader)
        val function = NativeLibrary.getInstance(dll.absolutePath).getFunction("ChatboxShowReplyToast")
        assertEquals(0x80070057.toInt(), function.invokeInt(arrayOf(WString("https://example.invalid/"))))
    }
}
