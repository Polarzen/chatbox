package dev.polarzen.chatbox

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopDeviceRuntimeTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun gatewayPortDefaultsOnFirstSetupAndReadsTheSavedPortWhileStopped() {
        val root = folder.root.toPath()
        assertEquals(18766, DesktopDeviceRuntime.configuredGatewayPort(root))

        Files.writeString(root.resolve("config.json"), """{"mode":"trusted","gatewayPort":18768}""")
        assertEquals(18768, DesktopDeviceRuntime.configuredGatewayPort(root))
    }
}
