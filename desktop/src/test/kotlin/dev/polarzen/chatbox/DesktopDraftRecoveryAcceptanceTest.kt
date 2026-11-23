package dev.polarzen.chatbox

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.sun.net.httpserver.HttpServer
import java.awt.image.BufferedImage
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import javax.sound.sampled.LineUnavailableException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopDraftRecoveryAcceptanceTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun unavailableMicrophoneLeavesExistingDraftAndDoesNotSubmit() = runBlocking {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            exchange.use { it.sendResponseHeaders(503, -1) }
        }
        server.start()
        try {
            val history = files.root.toPath().resolve("history.json")
            val store = DesktopStore(history)
            val profile = store.createProfile("http://127.0.0.1:${server.address.port}")
            val key = SessionKey(profile.id, profile.currentSession)
            val image = files.newFile("pending.png")
            ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", image)
            val draft = ComposerDraft("保留图片和文字", listOf(image.toPath()))
            store.saveDraft(key, store.nextDraftRevision(), draft)
            val notice = CompletableDeferred<Pair<SessionKey, String>>()
            val composer = DesktopComposerSession(store, this) { owner, message ->
                if (message != null) notice.complete(owner to message)
            }
            composer.open(key)
            composer.startMic(key) { directory ->
                MicRecording.start(directory) { throw LineUnavailableException("no input device") }
            }
            val (owner, message) = withTimeout(5000) { notice.await() }
            assertEquals(key, owner)
            assertTrue(message.contains("no input device"))
            assertNull(composer.recordingKey)
            assertEquals(draft, composer.editor(key)!!.draft)
            composer.flushForExit()
            val reopened = DesktopStore(history)
            assertEquals(draft, reopened.readDraft(key))
            assertTrue(reopened.sessionContent(key.profileId, key.sessionId).entries.isEmpty())
            assertTrue(image.isFile)
            Files.list(store.managedMediaDirectory).use { assertEquals(0L, it.count()) }
            assertEquals(0, requests.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun closeDuringImeCompositionRecoversLastCommittedEditAndItsAttachments() = runBlocking {
        val file = files.root.toPath().resolve("history.json")
        val store = DesktopStore(file)
        val profile = store.createProfile("http://127.0.0.1:7101")
        val key = SessionKey(profile.id, profile.currentSession)
        val attachments = listOf(files.newFile("second.jpg").toPath(), files.newFile("first.png").toPath())
        store.saveDraft(key, store.nextDraftRevision(), ComposerDraft("原文", attachments))
        val composer = DesktopComposerSession(store, this) { _, message -> error("Unexpected notice: $message") }
        composer.open(key)
        val committed = "已确认\n下一行"
        composer.input(key, TextFieldValue(committed, selection = TextRange(committed.length)))
        val composing = committed + "zhong"
        composer.input(key, TextFieldValue(composing, selection = TextRange(composing.length),
            composition = TextRange(committed.length, composing.length)))
        assertEquals(composing, composer.editor(key)!!.field.text)
        composer.flushForExit()
        assertEquals(ComposerDraft(committed, attachments), DesktopStore(file).readDraft(key))

        val finished = committed + "中"
        composer.input(key, TextFieldValue(finished, selection = TextRange(finished.length)))
        composer.flushForExit()
        assertEquals(ComposerDraft(finished, attachments), DesktopStore(file).readDraft(key))
    }
}
