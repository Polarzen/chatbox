package dev.polarzen.chatbox

import java.net.URI

/** Build the owner WebUI destination from the already verified service address. */
internal fun desktopKeyDetailsSettingsUri(verifiedBase: String): URI {
    endpoint(verifiedBase, allowLoopbackHttp = true)
    val base = URI(verifiedBase.trim())
    return URI(base.toASCIIString().trimEnd('/') + "/#/settings?section=settings-openapi")
}
