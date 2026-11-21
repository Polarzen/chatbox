package dev.polarzen.chatbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class LauncherInstanceResolverTest {
    private val firstIdentity = "00000000-0000-4000-8000-000000000001"
    private val secondIdentity = "00000000-0000-4000-8000-000000000002"

    @Test fun findsVerifiedLauncherInstanceAndTracksChangedPort() {
        val directory = Files.createTempDirectory("launcher-instances-")
        try {
            Files.createDirectories(directory.resolve(firstIdentity))
            Files.createDirectories(directory.resolve(secondIdentity))
            val processes = listOf(
                LauncherProcess(10, null, "C:\\Apps\\AstrBotLauncher\\astrbot-launcher.exe"),
                LauncherProcess(20, 10, directory.resolve(firstIdentity).resolve("venv\\Scripts\\python.exe").toString()),
                LauncherProcess(30, 20, "C:\\Users\\test\\components\\python.exe"),
                LauncherProcess(40, 10, directory.resolve(secondIdentity).resolve("venv\\Scripts\\python.exe").toString()),
                LauncherProcess(50, 40, "C:\\Users\\test\\components\\python.exe"),
                LauncherProcess(60, null, "C:\\Users\\test\\manual-python.exe"),
            )
            val first = LauncherInstanceResolver.discover(
                directory, processes,
                listOf(
                    LauncherListener("127.0.0.1", 18759, 30),
                    LauncherListener("127.0.0.1", 23990, 50),
                    LauncherListener("127.0.0.1", 23991, 60),
                    LauncherListener("0.0.0.0", 23992, 30),
                    LauncherListener("127.0.0.1", 18999, 30),
                ),
            ) { it != 18999 }
            assertEquals(
                listOf(
                    LauncherInstance(firstIdentity, "http://127.0.0.1:18759"),
                    LauncherInstance(secondIdentity, "http://127.0.0.1:23990"),
                ),
                first,
            )
            assertEquals(
                LauncherInstance(firstIdentity, "http://127.0.0.1:18759"),
                LauncherInstanceResolver.matchEnteredUrl("http://127.0.0.1:18759/", first),
            )
            assertNull(LauncherInstanceResolver.matchEnteredUrl("http://127.0.0.1:23991", first))
            assertNull(LauncherInstanceResolver.matchEnteredUrl("http://localhost:18759", first))

            val changedPort = LauncherInstanceResolver.discover(
                directory, processes, listOf(LauncherListener("127.0.0.1", 25123, 30)),
            ) { true }
            assertEquals(listOf(LauncherInstance(firstIdentity, "http://127.0.0.1:25123")), changedPort)
            assertEquals("http://127.0.0.1:25123", LauncherInstanceResolver.currentUrl(firstIdentity, changedPort))
            assertNull(LauncherInstanceResolver.currentUrl(secondIdentity, changedPort))
            assertNull(LauncherInstanceResolver.currentUrl("../$firstIdentity", changedPort))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun rejectsAmbiguousPortAndIdentityMismatch() {
        val directory = Files.createTempDirectory("launcher-instances-")
        try {
            Files.createDirectories(directory.resolve(firstIdentity))
            val processes = listOf(
                LauncherProcess(10, null, "C:\\Apps\\AstrBotLauncher\\astrbot-launcher.exe"),
                LauncherProcess(20, 10, directory.resolve(firstIdentity).resolve("venv\\Scripts\\python.exe").toString()),
                LauncherProcess(30, 20, "C:\\Users\\test\\components\\python.exe"),
            )
            val ambiguous = LauncherInstanceResolver.discover(
                directory, processes,
                listOf(
                    LauncherListener("127.0.0.1", 18759, 30),
                    LauncherListener("127.0.0.1", 25123, 30),
                ),
            ) { true }
            assertEquals(emptyList<LauncherInstance>(), ambiguous)

            val missingLauncherAncestor = processes.map { it.copy(parentPid = if (it.pid == 20L) 999L else it.parentPid) }
            val mismatch = LauncherInstanceResolver.discover(
                directory, missingLauncherAncestor, listOf(LauncherListener("127.0.0.1", 18759, 30)),
            ) { true }
            assertEquals(emptyList<LauncherInstance>(), mismatch)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
