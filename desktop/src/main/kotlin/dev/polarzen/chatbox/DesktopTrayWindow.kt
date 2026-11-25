package dev.polarzen.chatbox

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.awt.AWTException
import java.awt.Dialog
import java.awt.EventQueue
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.imageio.ImageIO
import java.util.concurrent.FutureTask

internal class DesktopTrayWindow(
    private val frame: Frame,
    private val onShow: () -> Unit,
    private val onExit: () -> Unit,
) : AutoCloseable {
    private var icon: TrayIcon? = null

    val trayAvailable: Boolean
        get() = onTrayEdt { icon != null }

    fun installTray(): Boolean = onTrayEdt {
        if (icon != null) return@onTrayEdt true
        try {
            if (!SystemTray.isSupported()) return@onTrayEdt false
            val menu = PopupMenu().apply {
                add(MenuItem("显示 Chat Box").apply { addActionListener { onShow() } })
                add(MenuItem("退出 Chat Box").apply { addActionListener { onExit() } })
            }
            val trayImage = ImageIO.read(requireNotNull(javaClass.getResource("/chat-box.png")))
            val trayIcon = TrayIcon(trayImage, "Chat Box", menu)
            trayIcon.isImageAutoSize = true
            trayIcon.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (event.button == MouseEvent.BUTTON1 && event.clickCount == 1) onShow()
                }
            })
            SystemTray.getSystemTray().add(trayIcon)
            icon = trayIcon
            true
        } catch (_: AWTException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun isForeground(): Boolean = onTrayEdt { foregroundWindow() != Foreground.OTHER }

    fun hasVisibleDialog(): Boolean = onTrayEdt { visibleDialog(frame) != null }

    fun isMainForeground(): Boolean = onTrayEdt {
        foregroundWindow() == Foreground.MAIN && visibleDialog(frame) == null
    }

    fun foreground(): Boolean = onTrayEdt {
        if (!frame.isDisplayable) return@onTrayEdt false
        if (frame.extendedState and Frame.ICONIFIED != 0) {
            frame.extendedState = frame.extendedState and Frame.ICONIFIED.inv()
        }
        frame.isVisible = true
        val target = visibleDialog(frame) ?: frame
        target.toFront()
        onWindows {
            trayUser32.SetForegroundWindow(Native.getWindowPointer(target))
        }
        if (target === frame) target.requestFocus()
        target === frame && foregroundWindow() == Foreground.MAIN
    }

    override fun close() = onTrayEdt {
        icon?.let { SystemTray.getSystemTray().remove(it) }
        icon = null
    }

    private fun foregroundWindow(): Foreground {
        if (!frame.isShowing || frame.extendedState and Frame.ICONIFIED != 0) return Foreground.OTHER
        if (!Platform.isWindows()) {
            val active = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
            return when {
                active === frame -> Foreground.MAIN
                generateSequence(active) { it.owner }.any { it === frame } -> Foreground.OWNED
                else -> Foreground.OTHER
            }
        }
        return onWindows {
            val main = Native.getWindowPointer(frame)
            val active = trayUser32.GetForegroundWindow() ?: return@onWindows Foreground.OTHER
            if (active == main) return@onWindows Foreground.MAIN
            var owner: Pointer? = active
            while (owner != null) {
                owner = trayUser32.GetWindow(owner, GW_OWNER)
                if (owner == main) return@onWindows Foreground.OWNED
            }
            val owned = Window.getWindows().any { candidate ->
                candidate.isShowing && generateSequence(candidate.owner) { it.owner }.any { it === frame } &&
                    Native.getWindowPointer(candidate) == active
            }
            if (owned) Foreground.OWNED else Foreground.OTHER
        } ?: Foreground.OTHER
    }

    private fun visibleDialog(owner: Window): Dialog? {
        for (child in owner.ownedWindows.reversed()) {
            if (!child.isShowing) continue
            visibleDialog(child)?.let { return it }
            if (child is Dialog) return child
        }
        return null
    }

    private enum class Foreground { MAIN, OWNED, OTHER }
}

private const val GW_OWNER = 4

private interface TrayUser32 : StdCallLibrary {
    fun GetForegroundWindow(): Pointer?
    fun GetWindow(window: Pointer, command: Int): Pointer?
    fun SetForegroundWindow(window: Pointer): Int
}

private val trayUser32 by lazy { Native.load("user32", TrayUser32::class.java) }

private inline fun <T> onWindows(action: () -> T): T? {
    if (!Platform.isWindows()) return null
    return try {
        action()
    } catch (_: LinkageError) {
        null
    } catch (_: RuntimeException) {
        null
    }
}

private fun <T> onTrayEdt(action: () -> T): T {
    if (EventQueue.isDispatchThread()) return action()
    val task = FutureTask(action)
    EventQueue.invokeAndWait(task)
    return task.get()
}
