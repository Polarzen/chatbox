package dev.polarzen.chatbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DesktopConnectionTest {
    @Test fun delayedProbeCannotCommitAfterProfileSwitchOrNewerCandidate() {
        val gate = DesktopConnectionRequestGate()
        val activeBinding = AtomicReference<String?>("original")

        val aSelection = ConnectionSelection("profile-a", "http://127.0.0.1:18111", "a-slow")
        val aRequest = gate.begin(aSelection)
        val probeEntered = CountDownLatch(1)
        val releaseProbe = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val staleProfileCommit = executor.submit<Boolean> {
                probeEntered.countDown()
                check(releaseProbe.await(5, TimeUnit.SECONDS))
                gate.commitIfCurrent(aRequest, aSelection) { activeBinding.set("stale-profile-a") }
            }
            assertTrue("slow verification should reach the probe", probeEntered.await(5, TimeUnit.SECONDS))

            val bSelection = ConnectionSelection("profile-b", "https://service-b.invalid", "b-key")
            val bRequest = gate.begin(bSelection)
            assertTrue(gate.commitIfCurrent(bRequest, bSelection) { activeBinding.set("profile-b") })

            releaseProbe.countDown()
            assertFalse("the completed probe is no longer current", gate.isCurrent(aRequest, aSelection))
            assertFalse("the late A result must not commit after switching to B", staleProfileCommit.get(5, TimeUnit.SECONDS))
            assertEquals("profile-b", activeBinding.get())

            val slowCandidate = ConnectionSelection("profile-a", "http://127.0.0.1:18111", "a-slow-candidate")
            val slowCandidateRequest = gate.begin(slowCandidate)
            val newerCandidate = ConnectionSelection("profile-a", "http://127.0.0.1:18111", "a-new-candidate")
            val newerCandidateRequest = gate.begin(newerCandidate)
            assertTrue(gate.commitIfCurrent(newerCandidateRequest, newerCandidate) {
                activeBinding.set("a-new-candidate")
            })
            assertFalse("the prior key candidate is no longer current", gate.isCurrent(slowCandidateRequest, slowCandidate))
            assertFalse("an older key result must not overwrite a newer candidate", gate.commitIfCurrent(
                slowCandidateRequest, slowCandidate,
            ) { activeBinding.set("a-slow-candidate") })
            assertEquals("a-new-candidate", activeBinding.get())
        } finally {
            releaseProbe.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun capturedSendTargetKeepsItsVerifiedEndpointAndTokenAfterSelectionChanges() {
        val addressByProfile = mapOf(
            "profile-a" to "http://127.0.0.1:18111",
            "profile-b" to "https://service-b.invalid",
        )
        var selectedProfile = "profile-a"
        val captured = DesktopSendTarget(
            profileId = "profile-a",
            address = addressByProfile.getValue("profile-a"),
            token = "fixture-secret-a",
            allowLoopbackHttp = true,
            bindingGeneration = 7L,
        ) {
            check(addressByProfile["profile-a"] == "http://127.0.0.1:18111")
        }

        selectedProfile = "profile-b"
        val current = DesktopSendTarget(
            profileId = selectedProfile,
            address = addressByProfile.getValue(selectedProfile),
            token = "fixture-secret-b",
            allowLoopbackHttp = false,
            bindingGeneration = 8L,
        ) {}

        assertNotSame(captured, current)
        assertEquals("profile-a", captured.profileId)
        assertEquals("http://127.0.0.1:18111", captured.address)
        assertEquals("fixture-secret-a", captured.token)
        assertTrue(captured.allowLoopbackHttp)
        assertTrue(runCatching { captured.checkTarget() }.isSuccess)
        assertFalse(captured.toString().contains(captured.token))
    }
}
