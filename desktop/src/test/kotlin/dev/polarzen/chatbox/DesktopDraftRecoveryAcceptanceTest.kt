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
import kotlinx.coroutines.yield
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
    fun immediateSendSeesBusyClipboardAfterEarlierBitmapHasFinished() = runBlocking {
        val history = files.root.toPath().resolve("history.json")
        val store = DesktopStore(history)
        val profile = store.createProfile("http://127.0.0.1:7101")
        val key = SessionKey(profile.id, profile.currentSession)
        val notices = mutableListOf<String?>()
        val composer = DesktopComposerSession(store, this) { _, message -> notices.add(message) }
        composer.open(key)
        composer.input(key, TextFieldValue("保留"))
        assertTrue(composer.paste(key) { ClipboardImages.Bitmap(BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB)) })
        val busy = captureClipboardImages { throw IllegalStateException("busy") } as ClipboardImages.Failure
        assertTrue(composer.paste(key) { busy })
        // Dispatch captures this deferred synchronously at the send click.
        val pendingAtClick = checkNotNull(composer.pendingImport(key))
        assertEquals(busy.message, pendingAtClick.await())
        assertEquals(busy.message, notices.last())
        composer.flushForExit()
        val draft = DesktopStore(history).readDraft(key)
        assertEquals("保留", draft.text)
        val image = ImageIO.read(draft.attachments.single().toFile())
        assertEquals(2, image.width)
        assertEquals(3, image.height)
    }

    @Test
    fun reselectingMovedImageReplacesOnlyItsOriginalSlot() = runBlocking {
        val history = files.root.toPath().resolve("history.json")
        val store = DesktopStore(history)
        val profile = store.createProfile("http://127.0.0.1:7101")
        val key = SessionKey(profile.id, profile.currentSession)
        val originals = listOf("z.png", "moved.png", "a.png").map { name ->
            files.newFile(name).also {
                ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it)
            }.toPath()
        }
        val draft = ComposerDraft("顺序", originals)
        store.saveDraft(key, store.nextDraftRevision(), draft)
        val moved = Files.move(originals[1], files.root.toPath().resolve("renamed.png"))
        val composer = DesktopComposerSession(store, this) { _, _ -> }
        composer.open(key)
        composer.replaceMissingAttachment(key, originals[1], listOf(originals.first().toFile()))
        assertTrue(checkNotNull(composer.pendingImport(key)).await() != null)
        assertEquals(draft, composer.editor(key)!!.draft)
        yield() // The next picker interaction arrives after the rejected import has settled.
        composer.replaceMissingAttachment(key, originals[1], listOf(moved.toFile()))
        assertNull(checkNotNull(composer.pendingImport(key)).await())
        composer.flushForExit()
        val expected = listOf(originals[0], moved, originals[2])
        assertEquals(ComposerDraft("顺序", expected), DesktopStore(history).readDraft(key))
        expected.forEach { assertTrue(Files.isRegularFile(it)) }
        assertTrue(Files.notExists(originals[1]))
    }

    @Test
    fun legacyAdoptionDrainsPendingPasteIntoTheTargetSession() = runBlocking {
        val history = files.root.toPath().resolve("history.json")
        Files.writeString(history, """{"servers":{"http://127.0.0.1:7101":{"session":"legacy-id"}}}""")
        val store = DesktopStore(history)
        val legacy = store.profiles.single { it.offlineOnly }
        val key = SessionKey(legacy.id, legacy.currentSession)
        val target = store.ensureProfile("launcher:verified", "https://fixture.example")
        val composer = DesktopComposerSession(store, this) { _, message -> check(message == null) }
        composer.open(key)
        composer.input(key, TextFieldValue("正在迁移的草稿"))
        composer.paste(key) { ClipboardImages.Bitmap(BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB)) }
        val sessionIds = setOf(key.sessionId)
        val adopted = composer.withLegacyDraftsSaved(setOf(legacy.id), sessionIds) {
            store.adoptVerifiedSessions(target.id, target.address, sessionIds)
        }
        assertEquals(sessionIds, adopted)
        assertNull(composer.editor(key))
        composer.flushForExit()
        val reopened = DesktopStore(history)
        assertEquals(ComposerDraft(), reopened.readDraft(key))
        val draft = reopened.readDraft(SessionKey(target.id, key.sessionId))
        assertEquals("正在迁移的草稿", draft.text)
        assertEquals(3, ImageIO.read(draft.attachments.single().toFile()).width)
    }

    @Test
    fun mixedImeBatchRecoversCommittedCharactersAndOriginalAttachmentOrder() = runBlocking {
        val history = files.root.toPath().resolve("history.json")
        val store = DesktopStore(history)
        val profile = store.createProfile("http://127.0.0.1:7101")
        val key = SessionKey(profile.id, profile.currentSession)
        val composer = DesktopComposerSession(store, this) { _, message ->
            check(message == null) { "Unexpected notice: $message" }
        }
        composer.open(key)
        composer.input(key, TextFieldValue("qian", selection = TextRange(4), composition = TextRange(0, 4)))
        composer.flushForExit()
        assertEquals(ComposerDraft(), DesktopStore(history).readDraft(key))

        val images = listOf("z.png", "a.png").map { name ->
            files.newFile(name).also {
                ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it)
            }
        }
        composer.addFiles(key, images)
        composer.input(key, TextFieldValue("前", selection = TextRange(1)))
        composer.input(key, TextFieldValue("前国ren", selection = TextRange(5), composition = TextRange(2, 5)))
        composer.flushForExit()
        val paths = images.map { it.toPath() }
        assertEquals(ComposerDraft("前国", paths), DesktopStore(history).readDraft(key))
        assertEquals("前国ren", composer.editor(key)!!.field.text)

        composer.input(key, TextFieldValue("前旧后", selection = TextRange(1, 2)))
        composer.input(key, TextFieldValue("前国ren后", selection = TextRange(5), composition = TextRange(2, 5)))
        composer.flushForExit()
        assertEquals(ComposerDraft("前国后", paths), DesktopStore(history).readDraft(key))
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
