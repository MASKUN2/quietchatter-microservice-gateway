package com.quietchatter.gateway

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient

sealed class RefreshOutcome {
    data class Success(val memberId: String, val setCookieHeaders: List<String>) : RefreshOutcome()
    data object SessionExpired : RefreshOutcome()
    data object Unavailable : RefreshOutcome()
}

private data class RefreshResponseBody(val memberId: String)

@Component
class TokenRefreshClient(
    @Value("\${MEMBER_SERVICE_URL:http://localhost:8083}") memberServiceUrl: String,
    @Value("\${INTERNAL_SECRET:default-internal-secret}") private val internalSecret: String
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val restClient = RestClient.builder().baseUrl(memberServiceUrl).build()

    fun rotate(refreshTokenValue: String): RefreshOutcome {
        return try {
            val response = restClient.post()
                .uri("/internal/auth/refresh")
                .header("X-Internal-Secret", internalSecret)
                .header("X-Refresh-Token", refreshTokenValue)
                .retrieve()
                .toEntity(RefreshResponseBody::class.java)
            val memberId = response.body?.memberId ?: return RefreshOutcome.SessionExpired
            val setCookieHeaders = response.headers[HttpHeaders.SET_COOKIE] ?: emptyList()
            RefreshOutcome.Success(memberId, setCookieHeaders)
        } catch (e: HttpClientErrorException) {
            RefreshOutcome.SessionExpired
        } catch (e: Exception) {
            log.error("Token refresh call to member service failed: {}", e.message)
            RefreshOutcome.Unavailable
        }
    }
}
