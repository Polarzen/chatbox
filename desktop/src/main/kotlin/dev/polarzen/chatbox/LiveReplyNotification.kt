package dev.polarzen.chatbox

internal fun interface ReplyToastPermit {
    fun show(): Boolean
}

/** Owned by one live send. Both terminal acceptance and validated cache writes arrive on the EDT. */
internal class LiveReplyNotification(private val submit: (ReplyToastPermit) -> Unit) {
    private var completed = false
    private var pending: ReplyToastPermit? = null
    private var hasReadableAttachment = false

    fun complete(entry: Entry, persisted: Boolean, capture: () -> ReplyToastPermit?) {
        if (completed) return
        completed = true
        if (persisted && entry.status == "已完成") pending = capture()
        if (entry.answer.isNotBlank() || hasReadableAttachment) deliverIfEligible()
    }

    fun initialAttachmentSaved() {
        hasReadableAttachment = true
        deliverIfEligible()
    }

    private fun deliverIfEligible() {
        val permit = pending ?: return
        pending = null
        submit(permit)
    }
}
