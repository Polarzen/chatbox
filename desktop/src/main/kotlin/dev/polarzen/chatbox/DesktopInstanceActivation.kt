package dev.polarzen.chatbox

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.EnumSet
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

internal sealed interface DesktopActivation {
    data object Show : DesktopActivation
    data class OpenReply(val target: ReplyDestination) : DesktopActivation
}

internal enum class ForwardOutcome { ACCEPTED, RETRYABLE, UNCERTAIN }

/**
 * The caller holds history.lock from before listen until after close. The callback confirms
 * acceptance on the UI thread, or throws IOException before accepting a request during shutdown.
 */
internal class DesktopInstanceActivation private constructor(
    private val key: String,
    private val record: Path,
    private val token: ByteArray,
    private val server: ServerSocket,
    private val onActivation: (DesktopActivation) -> Unit,
) : AutoCloseable {
    private fun serve() {
        while (!server.isClosed) {
            try {
                server.accept().use(::handle)
            } catch (_: IOException) {
                // A disconnected, stalled or malformed client does not end the listener.
            }
        }
    }

    private fun handle(socket: Socket) {
        val deadline = deadlineAfter(1200)
        val challenge = socket.receive(TOKEN_SIZE, deadline)
        val pid = intBytes(ProcessHandle.current().pid().toInt())
        socket.getOutputStream().write(pid + proof(token, key, challenge, pid))
        if (!MessageDigest.isEqual(token, socket.receive(TOKEN_SIZE, deadline))) return
        val length = ByteBuffer.wrap(socket.receive(4, deadline)).int
        if (length !in 1..MAX_REQUEST) return
        val request = socket.receive(length, deadline).toString(Charsets.US_ASCII)
        val activation = if (request == SHOW) DesktopActivation.Show else {
            val (directory, reply) = parseToastUri(request) ?: return
            if (directory != key) return
            reply
        }
        if (server.isClosed) {
            socket.getOutputStream().write(NACK)
            return
        }
        try {
            onActivation(activation)
        } catch (_: IOException) {
            socket.getOutputStream().write(NACK)
            return
        }
        synchronized(this) {
            if (!server.isClosed) socket.getOutputStream().write(ACK)
        }
    }

    @Synchronized override fun close() {
        if (server.isClosed) return
        server.close()
        Files.deleteIfExists(record)
    }

    companion object {
        private const val SHOW = "SHOW"
        private const val ACK = 1
        private const val NACK = 0
        private const val TOKEN_SIZE = 32
        private const val MAX_REQUEST = 3000
        private const val MAX_ID_BYTES = 1024
        private const val PREFIX = "polarzen-chatbox://reply/"
        private val keyPattern = Regex("[0-9a-f]{64}")
        private val uriPattern = Regex("([0-9a-f]{64})/([A-Za-z0-9_-]{1,1366})/([A-Za-z0-9_-]{1,1366})/(0|[1-9][0-9]{0,9})")
        private val random = SecureRandom()
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        fun directoryKey(dataDir: Path): String {
            val canonical = try {
                dataDir.toRealPath().toString()
            } catch (_: NoSuchFileException) {
                dataDir.toFile().canonicalPath
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()))
        }

        private fun encodeId(id: String): String {
            require(id.isNotBlank() && id.length <= MAX_ID_BYTES)
            val bytes = id.encodeToByteArray(throwOnInvalidSequence = true)
            require(bytes.size <= MAX_ID_BYTES)
            return encoder.encodeToString(bytes)
        }

        private fun decodeId(encoded: String): String? {
            val bytes = Base64.getUrlDecoder().decode(encoded)
            if (bytes.size > MAX_ID_BYTES || encoder.encodeToString(bytes) != encoded) return null
            return bytes.decodeToString(throwOnInvalidSequence = true).takeIf { it.isNotBlank() }
        }

        fun toastUri(directoryKey: String, target: ReplyDestination): String {
            require(keyPattern.matches(directoryKey) && target.entryIndex >= 0)
            return "$PREFIX$directoryKey/${encodeId(target.key.profileId)}/${encodeId(target.key.sessionId)}/${target.entryIndex}"
        }

        fun parseToastUri(uri: String): Pair<String, DesktopActivation.OpenReply>? {
            if (uri.length > MAX_REQUEST || !uri.startsWith(PREFIX)) return null
            val match = uriPattern.matchEntire(uri.substring(PREFIX.length)) ?: return null
            val (directory, profile, session, entry) = match.destructured
            return try {
                val key = SessionKey(decodeId(profile) ?: return null, decodeId(session) ?: return null)
                directory to DesktopActivation.OpenReply(ReplyDestination(key, entry.toIntOrNull() ?: return null))
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: CharacterCodingException) {
                null
            }
        }

        // Explorer protocol launches and a launcher with its own LOCALAPPDATA/TEMP use this same location.
        private fun recordPath(key: String): Path =
            Path.of(System.getProperty("user.home"), ".polarzen-chatbox-tmp", "$key.endpoint")

        private fun preparePrivateDirectory(directory: Path) {
            if (Platform.isWindows()) {
                Files.createDirectories(directory)
                check(Files.isDirectory(directory, NOFOLLOW_LINKS))
                val view = Files.getFileAttributeView(directory, AclFileAttributeView::class.java, NOFOLLOW_LINKS)
                val user = directory.fileSystem.userPrincipalLookupService.lookupPrincipalByName(windowsAccountName())
                val grant = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(user)
                    .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                    .setPermissions(EnumSet.allOf(AclEntryPermission::class.java)).build()
                if (view.acl != listOf(grant)) view.acl = listOf(grant)
            } else {
                val permissions = PosixFilePermissions.fromString("rwx------")
                Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(permissions))
                check(Files.isDirectory(directory, NOFOLLOW_LINKS))
                Files.setPosixFilePermissions(directory, permissions)
            }
        }

        private fun readRecord(path: Path): ByteArray =
            Files.newInputStream(path).use { it.readNBytes(4 + TOKEN_SIZE + 1) }

        fun listen(directoryKey: String, onActivation: (DesktopActivation) -> Unit): DesktopInstanceActivation {
            require(keyPattern.matches(directoryKey))
            val record = recordPath(directoryKey)
            preparePrivateDirectory(record.parent)
            val server = ServerSocket()
            try {
                server.bind(InetSocketAddress(loopback, 0))
                val token = randomBytes()
                val endpoint = intBytes(server.localPort) + token
                val listener = DesktopInstanceActivation(directoryKey, record, token, server, onActivation)
                val temporary = Files.createTempFile(record.parent, "endpoint-", ".tmp")
                try {
                    Files.write(temporary, endpoint)
                    thread(name = "Chatbox activation", isDaemon = true, block = listener::serve)
                    Files.move(temporary, record, ATOMIC_MOVE, REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(temporary)
                }
                return listener
            } catch (e: Throwable) {
                server.close()
                throw e
            }
        }

        fun forward(directoryKey: String, activation: DesktopActivation, waitMillis: Long = 2000): Boolean =
            forwardOutcome(directoryKey, activation, waitMillis) == ForwardOutcome.ACCEPTED

        fun forwardOutcome(directoryKey: String, activation: DesktopActivation, waitMillis: Long = 2000): ForwardOutcome {
            if (!keyPattern.matches(directoryKey) || waitMillis <= 0) return ForwardOutcome.RETRYABLE
            val request = try {
                when (activation) {
                    DesktopActivation.Show -> SHOW
                    is DesktopActivation.OpenReply -> toastUri(directoryKey, activation.target)
                }.toByteArray(Charsets.US_ASCII)
            } catch (_: IllegalArgumentException) {
                return ForwardOutcome.RETRYABLE
            } catch (_: CharacterCodingException) {
                return ForwardOutcome.RETRYABLE
            }

            val record = recordPath(directoryKey)
            val deadline = deadlineAfter(waitMillis.coerceAtMost(10_000))
            while (System.nanoTime() < deadline) {
                var dispatchStarted = false
                try {
                    val endpoint = readRecord(record)
                    if (endpoint.size == 4 + TOKEN_SIZE) {
                        val port = ByteBuffer.wrap(endpoint).int
                        if (port in 1..65535) Socket().use { socket ->
                            socket.connect(InetSocketAddress(loopback, port), remainingMillis(deadline))
                            val token = endpoint.copyOfRange(4, endpoint.size)
                            val challenge = randomBytes()
                            val output = socket.getOutputStream()
                            output.write(challenge)
                            val pid = socket.receive(4, deadline)
                            val signature = socket.receive(TOKEN_SIZE, deadline)
                            val processId = ByteBuffer.wrap(pid).int
                            if (processId == 0 || processId == -1 ||
                                !MessageDigest.isEqual(signature, proof(token, directoryKey, challenge, pid))) return@use
                            // Grant only after the server proves it owns this directory's current token.
                            if (Platform.isWindows()) {
                                try {
                                    foregroundUser32.AllowSetForegroundWindow(processId)
                                } catch (_: LinkageError) {
                                    // The primary can still show its window without foreground permission.
                                }
                            }
                            dispatchStarted = true
                            output.write(token + intBytes(request.size) + request)
                            return when (socket.receive(1, deadline)[0].toInt()) {
                                ACK -> ForwardOutcome.ACCEPTED
                                NACK -> ForwardOutcome.RETRYABLE
                                else -> ForwardOutcome.UNCERTAIN
                            }
                        }
                    }
                } catch (_: IOException) {
                    // An ack lost after dispatch must not replay an already queued navigation.
                    if (dispatchStarted) return ForwardOutcome.UNCERTAIN
                }
                val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (remaining <= 0) return ForwardOutcome.RETRYABLE
                try {
                    Thread.sleep(minOf(40, remaining))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return ForwardOutcome.RETRYABLE
                }
            }
            return ForwardOutcome.RETRYABLE
        }

        private fun intBytes(n: Int): ByteArray = ByteBuffer.allocate(4).putInt(n).array()
        private fun randomBytes(): ByteArray = ByteArray(TOKEN_SIZE).also(random::nextBytes)
        private fun proof(token: ByteArray, directory: String, challenge: ByteArray, pid: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(token, "HmacSHA256"))
                update(directory.toByteArray(Charsets.US_ASCII))
                update(challenge)
                doFinal(pid)
            }

        private fun deadlineAfter(ms: Long) = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)

        private fun remainingMillis(deadline: Long): Int {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw SocketTimeoutException()
            return TimeUnit.NANOSECONDS.toMillis(remaining).toInt().coerceAtLeast(1)
        }

        // Recompute the socket timeout for partial reads so drip-fed bytes cannot hold the receiver.
        private fun Socket.receive(length: Int, deadline: Long): ByteArray {
            val result = ByteArray(length)
            val input = getInputStream()
            var offset = 0
            while (offset < length) {
                soTimeout = remainingMillis(deadline)
                val count = input.read(result, offset, length - offset)
                if (count < 0) throw EOFException()
                offset += count
            }
            return result
        }

        private interface ForegroundUser32 : StdCallLibrary {
            fun AllowSetForegroundWindow(processId: Int): Boolean
        }
        private val foregroundUser32 by lazy { Native.load("user32", ForegroundUser32::class.java) }

        private interface Secur32 : StdCallLibrary {
            fun GetUserNameExW(format: Int, name: CharArray?, size: IntByReference): Byte
        }

        private fun windowsAccountName(): String {
            val secur32 = Native.load("secur32", Secur32::class.java)
            val nameSamCompatible = 2
            val errorMoreData = 234
            val size = IntByReference()
            secur32.GetUserNameExW(nameSamCompatible, null, size)
            if (Native.getLastError() != errorMoreData || size.value < 2) {
                throw IOException("Unable to determine the Windows account name length")
            }
            val name = CharArray(size.value)
            if (secur32.GetUserNameExW(nameSamCompatible, name, size).toInt() == 0 ||
                size.value !in 1 until name.size) {
                throw IOException("Unable to read the Windows account name")
            }
            return String(name, 0, size.value)
        }
    }
}
