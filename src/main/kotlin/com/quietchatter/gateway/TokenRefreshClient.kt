package com.quietchatter.gateway

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient

data class TokenRotationResult(
    val accessToken: String,
    val refreshToken: String,
    val memberId: String
)

sealed class RefreshOutcome {
    data class Success(val result: TokenRotationResult) : RefreshOutcome()
    data object SessionExpired : RefreshOutcome()
    data object Unavailable : RefreshOutcome()
}

@Component
class TokenRefreshClient(
    @Value("\${MEMBER_SERVICE_URL:http://localhost:8083}") memberServiceUrl: String,
    @Value("\${INTERNAL_SECRET:default-internal-secret}") private val internalSecret: String
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val restClient = RestClient.builder().baseUrl(memberServiceUrl).build()

    fun rotate(refreshTokenValue: String): RefreshOutcome {
        return try {
            val result = restClient.post()
                .uri("/internal/auth/refresh")
                .header("X-Internal-Secret", internalSecret)
                .header("X-Refresh-Token", refreshTokenValue)
                .retrieve()
                .body(TokenRotationResult::class.java)
            if (result != null) RefreshOutcome.Success(result) else RefreshOutcome.SessionExpired
        } catch (e: HttpClientErrorException) {
            RefreshOutcome.SessionExpired
        } catch (e: Exception) {
            log.error("Token refresh call to member service failed: {}", e.message)
            RefreshOutcome.Unavailable
        }
    }
}
