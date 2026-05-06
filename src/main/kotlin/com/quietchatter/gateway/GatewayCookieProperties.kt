package com.quietchatter.gateway

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.cookie")
data class GatewayCookieProperties(
    val domain: String? = null,
    val secure: Boolean = true,
    val sameSite: String = "Lax"
)
