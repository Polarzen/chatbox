package dev.polarzen.chatbox

import android.content.Context
import java.util.UUID

internal object AndroidDeviceStoreChecks {
    fun verify(context: Context,native: AndroidHistory) {
        val name = "device-test-${UUID.randomUUID()}.db"
        val b = DeviceBinding.create("https://original.invalid",UUID.randomUUID().toString())
        val a = DeviceRoomKey("windows","same-sid")
        val other = DeviceRoomKey("android","same-sid")
        val op = DeviceQueued(a,UUID.randomUUID().toString(),"first","first",null,true,false,0)
        val first = SharedTurn(1,op.id,"first","鲸".repeat(850_000),"complete")
        try {
            AndroidDeviceStore(context,name).use { cache ->
                cache.saveGrant(b,false)
                check(cache.grant() == (b to false))
                cache.saveGrant(b,true)
                cache.draft(a,"first",1)
                cache.begin(op)
                cache.saveTurns(a,listOf(first))
                check(cache.cursor(a) == 0L)
                cache.saveTurns(a,listOf(first),1)
                cache.saveTurns(other,listOf(first.copy(answer = "other username")),1)
                val media = DeviceQueued(other,UUID.randomUUID().toString(),"附件",null,DeviceLocalKey("https://phone.invalid","same-sid"),
                    false,true,2,authored = "说明",mediaKeyId = "named-key",nativePrompt = "说明")
                cache.begin(media)
                check(cache.claimMedia(media.id) && !cache.claimMedia(media.id))
                cache.mediaResult(media.id,"complete",first.answer)
                val persisted = cache.pending().single { it.media }
                check(persisted.dispatched && persisted.mediaKeyId == "named-key" && persisted.nativePrompt == "说明")
                check(persisted.finalStatus == "complete" && persisted.finalAnswer == first.answer)
                cache.release(media.id)
                cache.hide(a)
            }
            AndroidDeviceStore(context,name).use { cache ->
                check(cache.grant() == (b to true) && cache.pending() == listOf(op))
                check(cache.turns(a).single() == first && cache.turns(other).single().answer == "other username")
                check(cache.cursor(a) == 1L && cache.hidden() == setOf(a))
                cache.stageDraft(a,"edited",1)
                cache.stageDraft(a,"first",1)
                cache.flushDrafts()
                check(cache.pending().single().original == null && cache.drafts().single().text == "first")
                cache.stageDraft(a,"second draft",1)
                cache.saveGrant(b.copy(address = "https://replacement.invalid"),true)
                check(cache.trustedAddresses() == setOf(b.address,"https://replacement.invalid"))
                cache.forget()
                check(cache.grant() == null && cache.pending().isEmpty() && cache.turns(a).isEmpty() &&
                    cache.turns(other).isEmpty() && cache.trustedAddresses().isEmpty() && cache.drafts().isEmpty())
                val before = native.sessions("").toSet()
                cache.restoreDrafts(native)
                cache.restoreDrafts(native)
                val recovered = native.sessions("").filterNot { it in before }
                check(recovered.map { it.draft.text }.toSet() == setOf("first","second draft") && recovered.size == 2)
                check(recovered.all { it.sharedBase.isEmpty() && it.turns.isEmpty() && it.draft.attachments.isEmpty() })
            }
        } finally {
            AndroidDeviceStore(context,name).use { it.forget() }
            context.deleteDatabase(name)
        }
    }
}
