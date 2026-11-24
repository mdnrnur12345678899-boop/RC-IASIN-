package com.example.data

enum class ProxyProtocol(val displayName: String, val defaultPort: Int) {
    SOCKS5("SOCKS5", 1080),
    HTTP("HTTP", 8080);

    companion object {
        fun fromString(value: String?): ProxyProtocol =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SOCKS5
    }
}

data class ProxyConfig(
    val protocol: ProxyProtocol,
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = "",
    val label: String = "",
) {
    val displayTitle: String
        get() = label.trim().ifEmpty { "RC PROXY" }

    val hasCredentials: Boolean
        get() = username.isNotEmpty() || password.isNotEmpty()
}
