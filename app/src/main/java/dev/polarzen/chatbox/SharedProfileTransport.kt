package dev.polarzen.chatbox

internal data class ProfileImportTurn(val id: String, val question: String, val answer: String, val status: String)
internal data class ProfileDevice(val id: String, val name: String)
internal data class ProfileInvite(val code: String, val expiresIn: Long)
