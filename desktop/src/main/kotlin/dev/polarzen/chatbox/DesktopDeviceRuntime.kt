package dev.polarzen.chatbox

import org.json.JSONObject
import java.io.IOException
import java.net.JarURLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Blocking local pipe interface. The successful response may contain the Windows owner's bearer. */
internal object DesktopDeviceRuntime {
    private val moduleName = Regex("[A-Za-z_][A-Za-z_0-9]*[.]py")
    private val launcherId = Regex("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")
    private var installedScript: Path? = null
    private var interpreter: List<String>? = null

    fun call(request: JSONObject): JSONObject {
        fun failure(code: String) = JSONObject().put("ok", false).put("error", code)
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return failure("windows_required")
        val local = System.getenv("LOCALAPPDATA") ?: return failure("runtime_home_missing")
        val root = Path.of(local, "Polarzen", "Chatbox", "device-runtime")
        if (request.optString("action") != "configure" && !Files.exists(root.resolve("config.json"))) {
            return failure("not_configured")
        }
        return try {
            val script = installModules(root)
            val python = findPython(request.optString("launcherId")) ?: return failure("python_missing")
            val command = python + listOf("-E", "-s", "-X", "utf8", script.toString(), "--root", root.toString())
            val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            try {
                val reply = CompletableFuture.supplyAsync {
                    process.inputStream.use { it.readNBytes(65537) }
                }
                process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(request.toString()) }
                if (!process.waitFor(90, TimeUnit.SECONDS)) return failure("runtime_timeout")
                val bytes = reply.get(3, TimeUnit.SECONDS)
                if (bytes.size > 65536 || process.exitValue() != 0) failure("runtime_failed")
                else JSONObject(bytes.toString(Charsets.UTF_8))
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            failure("runtime_interrupted")
        } catch (_: Exception) {
            failure("runtime_failed")
        }
    }

    @Synchronized
    private fun findPython(preferred: String): List<String>? {
        interpreter?.takeIf { Files.isRegularFile(Path.of(it.first())) }?.let { return it }
        val candidates = mutableListOf<List<String>>()
        val instances = Path.of(System.getProperty("user.home"), ".astrbot_launcher", "instances")
        fun addLauncher(id: String) {
            val file = instances.resolve(id).resolve("venv/Scripts/python.exe")
            if (launcherId.matches(id) && Files.isRegularFile(file)) candidates.add(listOf(file.toString()))
        }
        addLauncher(preferred)
        val searchPath = System.getenv("PATH").orEmpty().split(';').mapNotNull {
            runCatching { Path.of(it.trim('"')) }.getOrNull()?.takeIf { dir -> dir.isAbsolute }
        }
        for (name in listOf("py.exe", "python.exe")) {
            for (dir in searchPath) {
                val path = dir.resolve(name)
                if (!dir.endsWith("WindowsApps") && Files.isRegularFile(path)) {
                    candidates.add(if (name == "py.exe") listOf(path.toString(), "-3") else listOf(path.toString()))
                }
            }
        }
        if (Files.isDirectory(instances)) {
            Files.list(instances).use { dirs -> dirs.sorted().forEach { addLauncher(it.fileName.toString()) } }
        }
        return candidates.distinct().firstOrNull { candidate ->
            val process = runCatching {
                ProcessBuilder(candidate + listOf("-I", "-c", "import sys;sys.exit(sys.version_info < (3,10))"))
                    .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            }.getOrNull() ?: return@firstOrNull false
            try {
                process.waitFor(4, TimeUnit.SECONDS) && process.exitValue() == 0
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }.also { interpreter = it }
    }

    @Synchronized
    private fun installModules(root: Path): Path {
        installedScript?.takeIf(Files::isRegularFile)?.let { return it }
        val entry = requireNotNull(javaClass.getResource("/shared_service/device_runtime.py"))
        val modules = sortedMapOf<String, ByteArray>()
        when (entry.protocol) {
            "file" -> Files.list(Path.of(entry.toURI()).parent).use { files ->
                files.filter { moduleName.matches(it.fileName.toString()) }.forEach {
                    modules[it.fileName.toString()] = Files.readAllBytes(it)
                }
            }
            "jar" -> {
                val jar = (entry.openConnection() as JarURLConnection).jarFile
                jar.entries().asSequence().filter { it.name.startsWith("shared_service/") }.forEach {
                    val name = it.name.removePrefix("shared_service/")
                    if (moduleName.matches(name)) modules[name] = jar.getInputStream(it).use { stream -> stream.readBytes() }
                }
            }
            else -> error("Unsupported resource location")
        }
        require("device_runtime.py" in modules && "service.py" in modules)
        val digest = MessageDigest.getInstance("SHA-256")
        for ((name, data) in modules) {
            digest.update(name.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(MessageDigest.getInstance("SHA-256").digest(data))
        }
        val directory = root.resolve("program").resolve(java.util.HexFormat.of().formatHex(digest.digest()))
        val script = directory.resolve("device_runtime.py")
        if (!Files.isRegularFile(script)) {
            Files.createDirectories(directory.parent)
            val temp = Files.createTempDirectory(directory.parent, "install-")
            try {
                for ((name, data) in modules) Files.write(temp.resolve(name), data)
                try {
                    Files.move(temp, directory, StandardCopyOption.ATOMIC_MOVE)
                } catch (e: IOException) {
                    if (!Files.isRegularFile(script)) throw e
                }
            } finally {
                if (Files.exists(temp)) {
                    Files.list(temp).use { files -> files.forEach(Files::delete) }
                    Files.delete(temp)
                }
            }
        }
        return script.also { installedScript = it }
    }
}
