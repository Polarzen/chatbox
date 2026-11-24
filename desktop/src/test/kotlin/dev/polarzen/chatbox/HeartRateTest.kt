package dev.polarzen.chatbox

import java.io.File
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HeartRateTest {
    private val band = HeartDevice("1234", "R", "手环")

    private fun TestScope.heartState() = HeartRateState(
        liveHeartRate = LiveHeartRate({ Instant.ofEpochMilli(testScheduler.currentTime) }, { testScheduler.currentTime * 1_000_000L }),
        nowMs = { testScheduler.currentTime },
    )

    private fun TestScope.packet(bpm: Int) = "BPM\t$bpm\t${testScheduler.currentTime}"

    private fun TestScope.connect(state: HeartRateState): Channel<String> {
        state.start(band)
        val run = state.active!!
        val events = Channel<String>(Channel.UNLIMITED)
        launch { state.read(run, events.receiveAsFlow()) }
        runCurrent()
        return events
    }

    @Test fun connectionAndFirstNotificationHaveIndependentDeadlines() = runTest {
        val state = heartState()
        connect(state)
        advanceTimeBy(29_999)
        assertNotNull(state.active)
        advanceTimeBy(1)
        runCurrent()
        assertNull(state.active)
        assertTrue(state.status.contains("连接超时"))

        val stream = connect(state)
        advanceTimeBy(25_000)
        stream.send("SUBSCRIBED")
        runCurrent()
        advanceTimeBy(9_999)
        assertNotNull(state.active)
        advanceTimeBy(1)
        runCurrent()
        assertNull(state.active)
        assertNull(state.bpm)
        assertTrue(state.status.contains("首个"))
    }

    @Test fun notificationsClearInvalidReadingsAndExpireAfterTheLastPacket() = runTest {
        val state = heartState()
        val source = state.liveHeartRate
        val stream = connect(state)
        stream.send(packet(259))
        runCurrent()
        assertEquals(259, state.bpm)
        advanceTimeBy(4_000)
        stream.send("SUBSCRIBED")
        runCurrent()
        assertEquals(259, state.bpm)
        assertEquals("实时心率", state.status)
        for (invalid in listOf("NO_READING", packet(0), "BPM\tbad\t${testScheduler.currentTime}")) {
            stream.send(invalid)
            runCurrent()
            assertNull(state.bpm)
            assertEquals("hello", source.appendTo("hello"))
            assertTrue(state.status.contains("佩戴"))
            stream.send(packet(77))
            runCurrent()
            assertEquals(77, state.bpm)
        }
        advanceTimeBy(6_000)
        stream.send(packet(77))
        runCurrent()
        advanceTimeBy(9_999)
        assertEquals(77, state.bpm)
        assertTrue(source.appendTo("hello").contains("77 BPM"))
        advanceTimeBy(1)
        runCurrent()
        assertNull(state.active)
        assertNull(state.bpm)
        assertEquals("hello", source.appendTo("hello"))
        assertTrue(state.status.contains("数据已中断"))
        assertEquals(band, state.selected)
    }

    @Test fun delayedHelperPacketsKeepTheirNativeReceiptTimeAndOriginalExpiry() = runTest {
        val state = heartState()
        val stream = connect(state)
        advanceTimeBy(20_000)
        for (invalid in listOf("BPM\t80", "BPM\t80\tbad", "BPM\t80\t20001", "BPM\t80\t10000")) {
            stream.send(invalid)
            runCurrent()
            assertNull(state.bpm)
            assertEquals("hello", state.liveHeartRate.appendTo("hello"))
        }
        stream.send("BPM\t81\t10050")
        runCurrent()
        assertEquals(81, state.bpm)
        assertTrue(state.liveHeartRate.appendTo("hello").contains("1970-01-01T00:00:10.050Z"))
        advanceTimeBy(49)
        assertEquals(81, state.bpm)
        advanceTimeBy(1)
        runCurrent()
        assertNull(state.bpm)
        assertNull(state.active)
        assertEquals("hello", state.liveHeartRate.appendTo("hello"))
    }

    @Test fun stoppedReaderCannotPublishQueuedDataOrFinishAReconnectToTheSameDevice() = runTest {
        val state = heartState()
        val old = connect(state)
        old.send(packet(89))
        runCurrent()
        state.stop()
        assertNull(state.bpm)
        assertEquals("hello", state.liveHeartRate.appendTo("hello"))
        assertEquals(band, state.selected)
        old.send(packet(214))
        old.close()
        val next = connect(state)
        assertNotNull(state.active)
        assertNull(state.bpm)
        assertEquals("hello", state.liveHeartRate.appendTo("hello"))
        next.send(packet(69))
        runCurrent()
        assertEquals(69, state.bpm)
        assertTrue(state.liveHeartRate.appendTo("hello").contains("69 BPM"))
        next.close()
        runCurrent()
        assertTrue(state.status.contains("断开"))
        assertNull(state.bpm)
        assertEquals("hello", state.liveHeartRate.appendTo("hello"))
    }

    @Test fun stageErrorsAndExplicitDisconnectSurviveTheReaderExit() = runTest {
        val state = heartState()
        for ((code, reason) in mapOf("RADIO" to "适配器", "SCAN" to "扫描失败", "DEVICE" to "靠近",
            "SERVICE" to "服务", "CHARACTERISTIC" to "特征", "SUBSCRIBE" to "订阅")) {
            state.start(if (code == "RADIO" || code == "SCAN") null else band)
            state.read(state.active!!, flowOf("ERROR\t$code", "ERROR\tREADER"))
            assertTrue(state.status, state.status.contains(reason))
            assertNull(state.active)
        }
        state.start(band)
        state.read(state.active!!, flowOf(packet(81), "DISCONNECTED", packet(99), "ERROR\tREADER"))
        assertNull(state.active)
        assertNull(state.bpm)
        assertTrue(state.status.contains("断开"))
    }

    @Test fun scanUpdatesNamesAndKeepsDifferentAddressTypesSelectable() = runTest {
        val state = heartState()
        state.start(null)
        state.read(state.active!!, flowOf("DEVICE\t100\tP\t", "DEVICE\t100\tR\t",
            "DEVICE\t100\tP\t手环", "DEVICE\t100\tP\t", "DEVICE\tbogus\tR\t错误"))
        assertEquals(listOf(HeartDevice("100", "P", "手环"), HeartDevice("100", "R", "")), state.devices.toList())
        assertTrue(state.devices.last().label.contains("00:64"))
        assertNotEquals(state.devices.first().label, state.devices.first().copy(type = "R").label)
        assertNull(state.active)
    }

    @Test fun stoppingReaderClosesStdinAndWaitsForCooperativeOrStuckProcess() = runBlocking {
        val classPath = listOf(HeartReaderFixture::class.java, Unit::class.java).joinToString(File.pathSeparator) {
            Path.of(it.protectionDomain.codeSource.location.toURI()).toString()
        }
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        for (mode in listOf("cooperate", "stall")) {
            lateinit var process: Process
            val lines = heartProcessLines {
                ProcessBuilder(java, "-cp", classPath, HeartReaderFixture::class.java.name, mode)
                    .start().also { process = it }
            }
            assertEquals("SUBSCRIBED", withTimeout(10_000) { lines.first() })
            assertFalse(process.isAlive)
            if (mode == "cooperate") assertEquals(17, process.exitValue())
        }
    }
}

object HeartReaderFixture {
    @JvmStatic fun main(args: Array<String>) {
        println("SUBSCRIBED")
        print("BPM\t")
        System.out.flush()
        if (args[0] == "cooperate") {
            while (System.`in`.read() != -1) { }
            kotlin.system.exitProcess(17)
        }
        Thread.sleep(30_000)
    }
}
