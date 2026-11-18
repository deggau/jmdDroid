package com.jmd.android

internal object RemoteCommandIdPolicy {
    private val allowedId = Regex("[A-Za-z0-9_:-]{1,120}")

    fun isValid(commandId: String): Boolean = allowedId.matches(commandId)

    fun executePath(commandId: String): String {
        require(isValid(commandId)) { "ID de comando inválido." }
        val encodedId = java.net.URLEncoder.encode(commandId, "UTF-8").replace("+", "%20")
        return "/api/v1/commands/$encodedId/execute"
    }
}
