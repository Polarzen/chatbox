package dev.polarzen.chatbox

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopDeviceHomeTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun recentKeepsBothSourcesAndSelectionWithDuplicateTitles() {
        val store = DesktopStore(folder.root.toPath().resolve("history.json"))
        fun add(address: String): SessionKey {
            val profile = store.createProfile(address)
            val chat = store.createSession(profile.id)
            store.saveSessionContent(profile.id, chat.copy(entries = listOf(Entry("同名", "回复", "已完成"))))
            return SessionKey(profile.id, chat.session)
        }
        val first = add("https://first.example")
        val second = add("https://second.example")
        val before = store.activeProfileId
        val rows = desktopRecentRows(store, first, emptyList()).filter { it.info.entryCount > 0 }
        assertEquals(setOf(first, second), rows.map { it.key }.toSet())
        assertEquals(first, rows.single { it.info.selected }.key)
        assertEquals(2, rows.map { it.label }.distinct().size)
        assertEquals(2, rows.map { it.info.id }.distinct().size)
        assertEquals(rows.map { it.info.activity }.sortedDescending(), rows.map { it.info.activity })
        assertEquals(before, store.activeProfileId)
    }

    @Test fun launcherPortComesFromLiveProcessAndAmbiguousRegistryLabelFallsBackToIdentity() {
        val id = "1ba4473f-f03d-4e22-8ef2-d2b4c52a28bc"
        val launcher = LauncherInstance(id, "http://127.0.0.1:6771")
        val file = folder.root.toPath().resolve("data.redb")
        fun manifest(name: String) = """{"instances":{"$id":{"name":"$name","port":6000}},"installed_versions":[]}"""
        Files.writeString(file, "metadata" + manifest("开发环境"))
        val names = launcherDisplayNames(file, listOf(launcher))
        assertEquals("开发环境", names[id])
        val saved = Profile("launcher:$id", "http://127.0.0.1:6000", "launcher", false, "")
        val choices = desktopSourceChoices(listOf(launcher), listOf(saved), DesktopDiscovery(names, null, null))
        assertEquals(listOf(DesktopSourceChoice(saved.id, launcher.url, "开发环境")), choices)
        Files.writeString(file, manifest("旧名字") + manifest("新名字"))
        assertTrue(launcherDisplayNames(file, listOf(launcher)).isEmpty())
    }
}
