package dev.polarzen.chatbox

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal data class LauncherInstance(val identity: String, val url: String)

/** Resolves only currently running AstrBot instances launched by AstrBot Launcher. */
internal object LauncherInstanceResolver {
    private val instancesDirectory: Path
        get() = Path.of(System.getProperty("user.home"), ".astrbot_launcher", "instances")

    fun matchEnteredUrl(url: String): LauncherInstance? {
        return matchEnteredUrl(url, runningInstances())
    }

    fun currentUrl(identity: String): String? {
        return currentUrl(identity, runningInstances())
    }

    internal fun matchEnteredUrl(url: String, verifiedInstances: List<LauncherInstance>): LauncherInstance? {
        val port = loopbackPort(url) ?: return null
        return verifiedInstances.singleOrNull { it.url == "http://127.0.0.1:$port" }
    }

    internal fun currentUrl(identity: String, verifiedInstances: List<LauncherInstance>): String? {
        val canonicalIdentity = canonicalUuid(identity) ?: return null
        return verifiedInstances.singleOrNull { it.identity == canonicalIdentity }?.url
    }

    fun runningInstances(): List<LauncherInstance> = discover(
        instancesDirectory,
        processSnapshot(),
        listenerSnapshot(),
        ::isAstrBotDashboard,
    )

    internal fun discover(
        instancesDirectory: Path,
        processes: List<LauncherProcess>,
        listeners: List<LauncherListener>,
        isAstrBotDashboard: (Int) -> Boolean,
    ): List<LauncherInstance> {
        val root = instancesDirectory.toAbsolutePath().normalize()
        val byPid = processes.associateBy { it.pid }
        val candidates = listeners.asSequence()
            .filter { it.address == "127.0.0.1" && it.port in 1..65535 }
            .mapNotNull { listener ->
                val identity = launcherIdentity(listener.pid, byPid, root) ?: return@mapNotNull null
                if (!runCatching { isAstrBotDashboard(listener.port) }.getOrDefault(false)) return@mapNotNull null
                LauncherInstance(identity, "http://127.0.0.1:${listener.port}")
            }
            .groupBy { it.identity }

        // A stable identity is usable only when it has exactly one verified API listener.
        return candidates.values
            .filter { instances -> instances.map { it.url }.distinct().size == 1 }
            .map { it.first() }
            .sortedBy { it.identity }
    }

    private fun loopbackPort(input: String): Int? = runCatching {
        val uri = java.net.URI(input.trim())
        require(uri.scheme.equals("http", ignoreCase = true))
        require(uri.host == "127.0.0.1" && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.path.isNullOrEmpty() || uri.path == "/")
        // Reuse the app's strict literal-loopback URL validation, including authority syntax checks.
        endpoint(input, allowLoopbackHttp = true)
        uri.port.takeIf { it >= 0 } ?: 80
    }.getOrNull()

    private fun launcherIdentity(
        listenerPid: Long,
        processes: Map<Long, LauncherProcess>,
        instancesDirectory: Path,
    ): String? {
        var process = processes[listenerPid] ?: return null
        val identities = linkedSetOf<String>()
        var foundLauncher = false
        val seen = hashSetOf<Long>()
        while (seen.add(process.pid)) {
            val executable = process.executable?.let { runCatching { Paths.get(it).toAbsolutePath().normalize() }.getOrNull() }
            if (executable != null) {
                if (executable.fileName?.toString().equals("astrbot-launcher.exe", ignoreCase = true)) {
                    foundLauncher = true
                }
                identityAtPath(executable, instancesDirectory)?.let(identities::add)
            }
            process = process.parentPid?.let(processes::get) ?: break
        }
        if (!foundLauncher || identities.size != 1) return null
        return identities.single()
    }

    private fun identityAtPath(executable: Path, instancesDirectory: Path): String? {
        if (!executable.startsWith(instancesDirectory) || executable == instancesDirectory) return null
        val relative = runCatching { instancesDirectory.relativize(executable) }.getOrNull() ?: return null
        if (relative.nameCount != 4 ||
            !relative.getName(1).toString().equals("venv", ignoreCase = true) ||
            !relative.getName(2).toString().equals("Scripts", ignoreCase = true) ||
            relative.getName(3).toString().lowercase() !in setOf("python.exe", "pythonw.exe")
        ) return null
        val identity = canonicalUuid(relative.getName(0).toString()) ?: return null
        if (!Files.isDirectory(instancesDirectory.resolve(identity))) return null
        return identity
    }

    private fun canonicalUuid(value: String): String? = runCatching {
        val parsed = UUID.fromString(value)
        parsed.toString().takeIf { it.equals(value, ignoreCase = true) }
    }.getOrNull()

    private fun isAstrBotDashboard(port: Int): Boolean = runCatching {
        val connection = URL("http://127.0.0.1:$port/").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 1_000
            connection.readTimeout = 1_000
            connection.useCaches = false
            connection.setRequestProperty("Cache-Control", "no-cache")
            if (connection.responseCode !in 200..299) return@runCatching false
            if (!connection.contentType.orEmpty().substringBefore(';').trim().equals("text/html", ignoreCase = true)) {
                return@runCatching false
            }
            val body = connection.inputStream.use { it.readBounded(8 * 1024).toString(Charsets.UTF_8) }
            Regex("(?is)<title>\\s*AstrBot Dashboard\\s*</title>").containsMatchIn(body)
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    private fun InputStream.readBounded(maxBytes: Int): ByteArray {
        val result = ByteArray(maxBytes)
        var used = 0
        while (used < maxBytes) {
            val count = read(result, used, maxBytes - used)
            if (count <= 0) break
            used += count
        }
        return result.copyOf(used)
    }

    private fun processSnapshot(): List<LauncherProcess> = runCatching {
        ProcessHandle.allProcesses().iterator().asSequence().mapNotNull { handle ->
            val executable = runCatching { handle.info().command().orElse(null) }.getOrNull()
            LauncherProcess(handle.pid(), handle.parent().map { it.pid() }.orElse(null), executable)
        }.toList()
    }.getOrDefault(emptyList())

    private fun listenerSnapshot(): List<LauncherListener> {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return emptyList()
        val systemRoot = System.getenv("SystemRoot")?.takeIf { it.isNotBlank() } ?: return emptyList()
        val netstat = Path.of(systemRoot, "System32", "netstat.exe")
        if (!Files.isRegularFile(netstat)) return emptyList()
        val process = runCatching {
            ProcessBuilder(netstat.toString(), "-ano", "-p", "tcp")
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return emptyList()
        val output = CompletableFuture.supplyAsync { runCatching { process.inputStream.bufferedReader().use { it.readText() } }.getOrDefault("") }
        return try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return emptyList()
            }
            if (process.exitValue() != 0) return emptyList()
            output.get(1, TimeUnit.SECONDS).lineSequence().mapNotNull { line ->
                val columns = line.trim().split(Regex("\\s+"))
                if (columns.size < 5 || !columns[0].equals("TCP", ignoreCase = true) ||
                    !columns[3].equals("LISTENING", ignoreCase = true)
                ) return@mapNotNull null
                val endpoint = columns[1]
                val separator = endpoint.lastIndexOf(':')
                if (separator <= 0) return@mapNotNull null
                val address = endpoint.substring(0, separator).removeSurrounding("[", "]")
                val port = endpoint.substring(separator + 1).toIntOrNull() ?: return@mapNotNull null
                val pid = columns[4].toLongOrNull() ?: return@mapNotNull null
                LauncherListener(address, port, pid)
            }.toList()
        } catch (_: Exception) {
            process.destroyForcibly()
            emptyList()
        }
    }

}

internal data class LauncherProcess(val pid: Long, val parentPid: Long?, val executable: String?)
internal data class LauncherListener(val address: String, val port: Int, val pid: Long)
