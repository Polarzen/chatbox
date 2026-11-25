package dev.polarzen.chatbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidRecentProfileTest {
    private val phone = DeviceProfile("a", "a", "https://chat.example", "phone", "工作", "phone1", "up1", true)
    private val pc = phone.copy(profile = "b", sourceId = "b", username = "pc", originDevice = "pc1")

    @Test fun rememberedProfileRemainsTheDefaultAcrossDevices() {
        assertEquals(pc, defaultDeviceProfile(listOf(phone, pc), "b", phone.base, phone.username))
        assertEquals(phone, defaultDeviceProfile(listOf(pc, phone), "removed", phone.base, phone.username))
    }

    @Test fun aUniqueAvailableProfileCanStartOnAFreshPhone() {
        assertEquals(pc, defaultDeviceProfile(listOf(pc), null, "", "new-phone"))
        assertNull(defaultDeviceProfile(emptyList(), null, phone.base, phone.username))
    }

    @Test fun ambiguousOwnershipRequiresSelectionEvenWhenNamesMatch() {
        assertNull(defaultDeviceProfile(listOf(pc, phone), null, "", "new-phone"))
        val duplicateSource = phone.copy(profile = "c", originDevice = "phone2")
        assertNull(defaultDeviceProfile(listOf(phone, duplicateSource), null, phone.base, phone.username))
    }

    @Test fun restoresOwnerAndIdBeforeFallingBackToVisibleHistory() {
        val local = AndroidDeviceRow("local:server", "local-room", "主题", 6, "本机", "", "server", "")
        val remote = AndroidDeviceRow("profile-a", "shared", "主题", 5, "电脑", "", null, "")
        val namesake = remote.copy(profile = "profile-b")
        val rows = listOf(local, namesake, remote)
        assertEquals(remote, initialChatRow(rows, "device", "profile-a", "shared", false, "server", "local-room"))
        assertEquals(local, initialChatRow(rows, "device", "gone", "shared", false, "server", "local-room"))
        assertEquals("empty-draft", initialChatRow(rows, "local", "", "empty-draft", true, "", "empty-draft")?.sid)
        assertNull(initialChatRow(emptyList(), "local", "gone", "deleted", false, "", "new"))
    }

    @Test fun recentNameFollowsTheHeaderWhileKeepingTheConversationTopic() {
        val profile = ConversationPreferences(profileName = "林舟")
        assertEquals(AndroidRecentText("林舟", "周末散步计划"), AndroidRecentText.from(profile, "周末散步计划"))
        assertEquals(AndroidRecentText("小林", "周末散步计划"),
            AndroidRecentText.from(profile.copy(peerName = "小林"), "周末散步计划"))
        assertEquals(AndroidRecentText("林舟", null), AndroidRecentText.from(profile, "林舟"))
        assertEquals(AndroidRecentText("助手", "草稿 · 附件"), AndroidRecentText.from(ConversationPreferences(), "草稿 · 附件"))
    }

    @Test fun recentRowsFollowTheSameOwnerRouteWhenOpeningFromTheDirectory() {
        val native = AndroidDeviceRow("local:server", "same-id", "主题", 0, "本机", "", "server", "")
        assertEquals("server", native.localChatBase(false))
        assertNull(native.localChatBase(true))
        assertEquals("", native.copy(profile = "phone-only:", nativeBase = "").localChatBase(true))
        assertNull(native.copy(profile = "pc", nativeBase = null).localChatBase(true))
    }
}
