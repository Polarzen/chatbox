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
}
