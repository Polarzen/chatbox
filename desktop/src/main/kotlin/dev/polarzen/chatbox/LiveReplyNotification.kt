package dev.polarzen.chatbox

/** Owned by one live send. Both terminal acceptance and validated cache writes arrive on the EDT. */
internal class LiveReplyNotification(private val notify: () -> Unit) {
    private var terminalEligible: Boolean? = null
    private var hasReadableAttachment = false

    fun complete(entry: Entry, persisted: Boolean, mayNotify: () -> Boolean) {
        if (terminalEligible != null) return
        terminalEligible = persisted && entry.status == "已完成" && mayNotify()
        if (entry.answer.isNotBlank() || hasReadableAttachment) deliverIfEligible()
    }

    fun initialAttachmentSaved() {
        hasReadableAttachment = true
        deliverIfEligible()
    }

    private fun deliverIfEligible() {
        if (terminalEligible == true) {
            terminalEligible = false
            notify()
        }
    }
}
