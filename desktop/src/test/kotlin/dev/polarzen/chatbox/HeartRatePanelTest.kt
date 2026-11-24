package dev.polarzen.chatbox

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class HeartRatePanelTest {
    @get:Rule val rule = createComposeRule()

    @Test fun closeKeepsTheHeaderLiveAndStopOffersTheSelectedDeviceAgain() {
        lateinit var state: HeartRateState
        val screenVisible = mutableStateOf(true)
        val device = HeartDevice("1234", "R", "测试手环")
        // Each helper process owns a fresh stream. Stopping the first read cancels its channel.
        val first = Channel<String>(Channel.UNLIMITED)
        val reconnect = Channel<String>(Channel.UNLIMITED)
        val connections = listOf(first, reconnect).iterator()
        rule.setContent {
            if (screenVisible.value) {
                state = rememberHeartRateState { selected ->
                    if (selected == null) flowOf("DEVICE\t1234\tR\t测试手环")
                    else {
                        assertEquals(device, selected)
                        connections.next().receiveAsFlow()
                    }
                }
                HeartRatePanel(state)
            }
        }
        rule.onNodeWithText("心率").performClick()
        rule.onNodeWithText("扫描设备").performClick()
        rule.waitUntil(5_000) { state.devices.isNotEmpty() && state.active == null }
        rule.onNodeWithText(device.label).performClick()
        first.trySend("BPM\t72\t${System.currentTimeMillis()}").getOrThrow()
        rule.waitUntil(5_000) { state.bpm == 72 }
        rule.onNodeWithText("72 BPM").assertIsDisplayed()
        rule.onNodeWithText("关闭面板").performClick()
        first.trySend("BPM\t79\t${System.currentTimeMillis()}").getOrThrow()
        rule.waitUntil(5_000) { state.bpm == 79 }
        rule.onNodeWithText("♥ 79").performClick()
        rule.onNodeWithText("停止").performClick()
        rule.onNodeWithText("已停止。").assertIsDisplayed()
        rule.onNodeWithText("重新连接 ${device.label}").performClick()
        reconnect.trySend("BPM\t83\t${System.currentTimeMillis()}").getOrThrow()
        rule.waitUntil(5_000) { state.bpm == 83 }
        rule.onNodeWithText("83 BPM").assertIsDisplayed()
        rule.runOnIdle { screenVisible.value = false }
        rule.runOnIdle {
            assertNull(state.active)
            assertNull(state.bpm)
            assertEquals("hello", state.liveHeartRate.appendTo("hello"))
        }
    }
}
