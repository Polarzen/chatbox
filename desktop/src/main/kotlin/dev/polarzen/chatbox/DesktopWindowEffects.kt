package dev.polarzen.chatbox

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.ptr.ByteByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.EventQueue
import java.awt.Window
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import kotlin.math.roundToInt

@Composable
internal fun DesktopWindowEffects(window: Window, appearance: DesktopAppearance, onFailure: (String) -> Unit) {
    if (!Platform.isWindows()) return
    val latestFailure = rememberUpdatedState(onFailure)
    val effects = remember(window) { WindowEffects(window) { latestFailure.value(it) } }
    DisposableEffect(effects) {
        onEdt { effects.attach() }
        onDispose { onEdt { effects.detach() } }
    }
    DisposableEffect(effects, appearance) {
        onEdt { effects.update(appearance) }
        onDispose {}
    }
}

private fun onEdt(action: () -> Unit) {
    if (EventQueue.isDispatchThread()) action() else EventQueue.invokeLater(action)
}

private interface User32 : StdCallLibrary {
    fun GetWindowLongW(window: Pointer, index: Int): Int
    fun SetWindowLongW(window: Pointer, index: Int, value: Int): Int
    fun SetWindowPos(window: Pointer, after: Pointer, x: Int, y: Int, width: Int, height: Int, flags: Int): Int
    fun SetLayeredWindowAttributes(window: Pointer, key: Int, alpha: Byte, flags: Int): Int
    fun GetLayeredWindowAttributes(window: Pointer, key: IntByReference, alpha: ByteByReference, flags: IntByReference): Int
}

private interface DwmApi : StdCallLibrary {
    fun DwmSetWindowAttribute(window: Pointer, attribute: Int, value: IntByReference, size: Int): Int
}

private val user32 by lazy { Native.load("user32", User32::class.java) }
private val dwm by lazy { Native.load("dwmapi", DwmApi::class.java) }
private const val GWL_EXSTYLE = -20
private const val WS_EX_LAYERED = 0x80000
private const val WS_EX_TOPMOST = 8
private const val LWA_ALPHA = 2

private class WindowEffects(private val window: Window, private val onFailure: (String) -> Unit) {
    private var desired: DesktopAppearance? = null
    private var peer: WindowsPeer? = null
    private val listener = HierarchyListener { event ->
        if (event.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L) {
            if (window.isDisplayable) desired?.let(::update) else peer = null
        }
    }

    fun attach() = window.addHierarchyListener(listener)

    fun update(appearance: DesktopAppearance) {
        desired = appearance
        if (!window.isDisplayable) return
        val errors = mutableListOf<String>()
        attempt("窗口外观", errors) {
            val current = peer ?: WindowsPeer(Native.getWindowPointer(window)).also { peer = it }
            current.applyAppearance(appearance, errors)
        }
        if (errors.isNotEmpty()) onFailure(errors.joinToString("；"))
    }

    fun detach() {
        window.removeHierarchyListener(listener)
        val errors = mutableListOf<String>()
        if (window.isDisplayable) attempt("恢复窗口透明度", errors) { peer?.restoreOpacity() }
        peer = null
        desired = null
        if (errors.isNotEmpty()) onFailure(errors.joinToString("；"))
    }
}

private inline fun attempt(label: String, errors: MutableList<String>, action: () -> Unit) {
    try {
        action()
    } catch (e: RuntimeException) {
        errors.add("${label}设置失败（${e.message ?: e.javaClass.simpleName}）")
    } catch (e: LinkageError) {
        errors.add("${label}设置失败（${e.message ?: e.javaClass.simpleName}）")
    }
}

private data class LayerAttributes(val key: Int, val alpha: Byte, val flags: Int)

private class WindowsPeer(private val handle: Pointer) {
    private var previous: DesktopAppearance? = null
    private var addedLayeredStyle = false
    private var previousAttributes: LayerAttributes? = null

    fun applyAppearance(next: DesktopAppearance, errors: MutableList<String>) {
        val last = previous
        previous = next
        if (last?.pinned != next.pinned) attempt("窗口置顶", errors) {
            if ((extendedStyle() and WS_EX_TOPMOST != 0) != next.pinned) {
                val after = Pointer.createConstant(if (next.pinned) -1L else -2L)
                // SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE
                checkWin32(user32.SetWindowPos(handle, after, 0, 0, 0, 0, 0x13), "SetWindowPos")
            }
        }
        if (last?.opacityPercent != next.opacityPercent) attempt("窗口透明度", errors) {
            if (next.opacityPercent == 100) restoreOpacity() else setOpacity(next.opacityPercent)
        }
        if (last?.dark != next.dark) attempt("标题栏颜色", errors) {
            val palette = if (next.dark) darkDesktopPalette else lightDesktopPalette
            setCaptionAttribute(20, if (next.dark) 1 else 0)
            setCaptionAttribute(35, palette.canvas.toColorRef())
            setCaptionAttribute(36, palette.ink.toColorRef())
        }
    }

    private fun extendedStyle(): Int {
        Native.setLastError(0)
        val style = user32.GetWindowLongW(handle, GWL_EXSTYLE)
        if (style == 0) checkLastError("GetWindowLongW")
        return style
    }

    private fun setExtendedStyle(style: Int) {
        Native.setLastError(0)
        if (user32.SetWindowLongW(handle, GWL_EXSTYLE, style) == 0) checkLastError("SetWindowLongW")
    }

    private fun setOpacity(percent: Int) {
        if (!addedLayeredStyle && previousAttributes == null) {
            val style = extendedStyle()
            if (style and WS_EX_LAYERED == 0) {
                setExtendedStyle(style or WS_EX_LAYERED)
                addedLayeredStyle = true
            } else {
                val key = IntByReference()
                val alpha = ByteByReference()
                val flags = IntByReference()
                checkWin32(user32.GetLayeredWindowAttributes(handle, key, alpha, flags), "GetLayeredWindowAttributes")
                previousAttributes = LayerAttributes(key.value, alpha.value, flags.value)
            }
        }
        val original = previousAttributes
        val alpha = (percent * 255 / 100.0).roundToInt().toByte()
        val result = user32.SetLayeredWindowAttributes(handle, original?.key ?: 0, alpha, (original?.flags ?: 0) or LWA_ALPHA)
        if (result == 0) {
            val code = Native.getLastError()
            restoreOpacity()
            error("SetLayeredWindowAttributes / Win32 $code")
        }
    }

    fun restoreOpacity() {
        if (addedLayeredStyle) {
            setExtendedStyle(extendedStyle() and WS_EX_LAYERED.inv())
            addedLayeredStyle = false
        } else {
            val original = previousAttributes ?: return
            checkWin32(user32.SetLayeredWindowAttributes(handle, original.key, original.alpha, original.flags), "SetLayeredWindowAttributes")
            previousAttributes = null
        }
    }

    private fun setCaptionAttribute(attribute: Int, value: Int) {
        val hr = dwm.DwmSetWindowAttribute(handle, attribute, IntByReference(value), Int.SIZE_BYTES)
        // Earlier Windows builds return E_INVALIDARG for newer caption attributes.
        check(hr >= 0 || hr == 0x80070057.toInt()) { "DwmSetWindowAttribute($attribute) / HRESULT 0x${hr.toUInt().toString(16)}" }
    }
}

private fun Color.toColorRef(): Int {
    val rgb = toArgb()
    return ((rgb and 0xff) shl 16) or (rgb and 0xff00) or ((rgb ushr 16) and 0xff)
}

private fun checkLastError(operation: String) {
    val code = Native.getLastError()
    check(code == 0) { "$operation / Win32 $code" }
}

private fun checkWin32(result: Int, operation: String) {
    check(result != 0) { "$operation / Win32 ${Native.getLastError()}" }
}
