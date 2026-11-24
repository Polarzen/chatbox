package dev.polarzen.chatbox

import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Dialog
import java.awt.EventQueue
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class DesktopShellApplicationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun defaultCloseAndMissingTrayAlwaysRequestExit() {
        for (tray in listOf(false, true)) {
            val state = DesktopShellVisibility()
            state.windowClose(DesktopShellSettings().closeToTray, tray)
            assertTrue(state.exiting)
            assertFalse(state.hideRequested)
            var quit = false
            state.finishExit(true, { quit = true }, { fail("Unexpected restore") })
            assertTrue(quit)
        }
        val withoutTray = DesktopShellVisibility()
        withoutTray.hide(false)
        assertFalse(withoutTray.hideRequested)
        withoutTray.windowClose(closeToTray = true, trayAvailable = false)
        assertTrue(withoutTray.exiting)
        assertTrue(withoutTray.visible)
    }

    @Test fun wakeDuringDraftFlushKeepsWindowVisibleAndExitWinsOverHide() {
        val state = DesktopShellVisibility()
        state.windowClose(closeToTray = true, trayAvailable = true)
        assertTrue(state.hideRequested)
        assertTrue(state.visible)
        state.show()
        state.finishHide(saved = true, trayAvailable = true)
        assertFalse(state.hideRequested)
        assertTrue(state.visible)
        state.hide(true)
        state.finishHide(saved = false, trayAvailable = true)
        assertTrue(state.visible)
        state.hide(true)
        state.finishHide(saved = true, trayAvailable = false)
        assertTrue(state.visible)
        state.hide(true)
        state.finishHide(saved = true, trayAvailable = true)
        assertFalse(state.visible)
        state.show()
        state.hide(true)
        state.exit()
        state.show()
        state.finishHide(saved = true, trayAvailable = true)
        assertTrue(state.visible)
        assertTrue(state.exiting)
        assertFalse(state.hideRequested)
    }

    @Test fun exitDuringHideUsesOneOutstandingSave() {
        val state = DesktopShellVisibility()
        var started = 0
        state.bindDraftSave { started++ }
        state.hide(true)
        state.exit()
        state.exit()
        assertTrue(state.exiting)
        assertFalse(state.hideRequested)
        assertEquals(1, started)
        var exited = false
        state.finishExit(true, { exited = true }, { fail("Unexpected restore") })
        assertTrue(exited)
    }

    @Test fun foregroundOrDisabledAtCompletionSkipsToastAndNativeSuppressionHasNoFallback() {
        var enabled = true
        var foreground = true
        val target = ReplyDestination(SessionKey("profile", "session"), 2)
        val attempts = mutableListOf<ReplyDestination>()
        val notifier = DesktopReplyNotifier({ enabled }, { foreground }) {
            attempts.add(it)
            false // Windows suppresses delivery.
        }
        fun completed(answer: String): LiveReplyNotification {
            val live = LiveReplyNotification { assertFalse(notifier.show(target)) }
            live.complete(Entry("q", answer, "已完成"), persisted = true, notifier::eligible)
            return live
        }
        completed("in foreground")
        enabled = false
        foreground = false
        completed("disabled")
        assertTrue(attempts.isEmpty())
        enabled = true
        val background = completed("")
        foreground = true
        background.initialAttachmentSaved()
        background.initialAttachmentSaved()
        assertEquals(listOf(target), attempts)
    }

    @Test fun ownedModalSuppressesAndPinnedWindowLeavesTheOtherForegroundWindowActive() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val main = Frame("Chatbox shell test")
        val other = Frame("Other shell test window")
        val owned = Dialog(main, "Chatbox modal", Dialog.ModalityType.DOCUMENT_MODAL)
        val tray = DesktopTrayWindow(main, {}, {})
        val otherWindow = DesktopTrayWindow(other, {}, {})
        val target = ReplyDestination(SessionKey("profile", "session"), 1)
        var attempts = 0
        val notifier = DesktopReplyNotifier({ true }, tray::isForeground) { attempts++; false }
        val nativeForeground = if (com.sun.jna.Platform.isWindows()) {
            com.sun.jna.NativeLibrary.getInstance("user32").getFunction("GetForegroundWindow")
        } else null
        fun active(window: java.awt.Window): Boolean = if (nativeForeground != null) {
            window.isShowing && nativeForeground.invokePointer(emptyArray()) == com.sun.jna.Native.getWindowPointer(window)
        } else {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow === window
        }
        fun awaitActive(window: java.awt.Window): Boolean {
            val deadline = System.nanoTime() + 2_000_000_000L
            while (System.nanoTime() < deadline) {
                var matches = false
                EventQueue.invokeAndWait { matches = active(window) }
                if (matches) return true
                Thread.sleep(20)
            }
            return false
        }
        try {
            EventQueue.invokeAndWait {
                main.setBounds(10, 10, 240, 140)
                other.setBounds(270, 10, 240, 140)
                owned.setBounds(20, 20, 220, 120)
                main.isVisible = true
                other.isVisible = true
                main.isAlwaysOnTop = true
                tray.foreground()
            }
            EventQueue.invokeLater { owned.isVisible = true }
            assumeTrue("A foreground-capable graphical session is required", awaitActive(owned))
            assertTrue(tray.hasVisibleDialog())
            assertTrue(tray.isForeground())
            assertFalse(tray.isMainForeground())
            assertFalse(notifier.eligible())
            EventQueue.invokeAndWait { owned.dispose(); otherWindow.foreground() }
            assumeTrue("The test window must obtain foreground", awaitActive(other))
            assertTrue(main.isAlwaysOnTop)
            assertTrue(notifier.eligible())
            assertFalse(notifier.show(target))
            assertEquals(1, attempts)
            EventQueue.invokeAndWait { assertTrue("Pinned Chatbox took foreground", active(other)) }
        } finally {
            EventQueue.invokeAndWait { owned.dispose(); main.dispose(); other.dispose() }
        }
    }

    @Test fun packagedProtocolAndSecondLaunchReachOnlyTheOwningDirectory() {
        val original = temp.newFolder().toPath()
        val other = temp.root.toPath().resolve("unused-data")
        val key = DesktopInstanceActivation.directoryKey(original)
        val reply = DesktopActivation.OpenReply(ReplyDestination(SessionKey("profile", "thread"), 7))
        val args = arrayOf("--open-toast", DesktopInstanceActivation.toastUri(key, reply.target))
        val events = mutableListOf<DesktopActivation>()
        val errors = mutableListOf<String>()
        fun secondary(command: Array<String>, data: java.nio.file.Path) {
            withDesktopInstance(command, data, errors::add) { _, _, _ -> fail("Started a second history owner") }
        }
        withDesktopInstance(args, original, errors::add) { ownKey, inbox, listening ->
            assertEquals(key, ownKey)
            assertTrue(listening)
            secondary(emptyArray(), original)
            secondary(args, original)
            secondary(args, other)
            // The cold click and all secondaries may arrive before the first window is composed.
            EventQueue.invokeAndWait { inbox.bind(handler = events::add) }
        }
        assertEquals(listOf(reply, DesktopActivation.Show, reply, reply), events)
        assertTrue(errors.isEmpty())
        secondary(args, other)
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("已失效"))
        assertFalse(Files.exists(other))
    }

    @Test fun rejectedClickTakesOverTheReleasedHistoryLockAfterExit() = checkExitHandoff(saved = true)

    @Test fun rejectedClickReturnsToTheOwnerWhenExitSaveFails() = checkExitHandoff(saved = false)

    private fun checkExitHandoff(saved: Boolean) {
        val directory = temp.newFolder().toPath()
        val key = DesktopInstanceActivation.directoryKey(directory)
        val reply = DesktopActivation.OpenReply(ReplyDestination(SessionKey("profile", "thread"), 3))
        val click = arrayOf("--open-toast", DesktopInstanceActivation.toastUri(key, reply.target))
        val executor = Executors.newSingleThreadExecutor()
        val rejected = CountDownLatch(1)
        val events = mutableListOf<DesktopActivation>()
        val errors = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val visibility = DesktopShellVisibility()
        lateinit var second: Future<Boolean>
        try {
            withDesktopInstance(emptyArray(), directory, errors::add) { _, inbox, listening ->
                assertTrue(listening)
                EventQueue.invokeAndWait {
                    visibility.exit()
                    inbox.bind(accepting = {
                        if (visibility.exiting) rejected.countDown()
                        !visibility.exiting
                    }, handler = events::add)
                }
                second = executor.submit<Boolean> {
                    var becamePrimary = false
                    withDesktopInstance(click, directory, errors::add) { _, successor, _ ->
                        becamePrimary = true
                        EventQueue.invokeAndWait { successor.bind(handler = events::add) }
                    }
                    becamePrimary
                }
                assertTrue(rejected.await(3, TimeUnit.SECONDS))
                EventQueue.invokeAndWait { visibility.finishExit(saved, inbox::dispose, {}) }
                if (!saved) assertFalse(second.get(4, TimeUnit.SECONDS))
            }
            assertEquals(saved, second.get(4, TimeUnit.SECONDS))
            assertTrue(errors.toString(), errors.isEmpty())
            assertEquals(listOf(reply), events)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun malformedCliNeverOpensOrCreatesHistory() {
        val data = temp.root.toPath().resolve("unused-data")
        val errors = mutableListOf<String>()
        for (args in listOf(arrayOf("--open-toast"), arrayOf("--open-toast", "file:///C:/"),
            arrayOf("--open-toast", "invalid", "extra"), arrayOf("polarzen-chatbox://reply/invalid"))) {
            withDesktopInstance(args, data, errors::add) { _, _, _ -> fail("Unexpected primary startup") }
        }
        assertEquals(4, errors.size)
        assertFalse(Files.exists(data))
    }
}
