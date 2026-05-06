package com.quietchatter.gateway

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JwtTokenServiceTest {

    private val secretKey = "your-very-long-secret-key-at-least-32-chars-long"
    private val jwtTokenService = JwtTokenService(secretKey)

    @Test
    fun `create and validate access token`() {
        val memberId = "test-member-123"
        val token = jwtTokenService.createNewAccessToken(memberId)

        assertNotNull(token)
        val extractedId = jwtTokenService.validateAndGetMemberId(token)
        assertEquals(memberId, extractedId)
    }

    @Test
    fun `invalid token should throw InvalidAuthTokenException`() {
        assertThrows(InvalidAuthTokenException::class.java) {
            jwtTokenService.validateAndGetMemberId("not-a-jwt")
        }
    }

    // 테스트용 액세스 토큰 생성 헬퍼 (JwtTokenService가 public API에서 제거되었으므로 직접 생성)
    private fun JwtTokenService.createNewAccessToken(memberId: String): String {
        val key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(secretKey.toByteArray(Charsets.UTF_8))
        return io.jsonwebtoken.Jwts.builder()
            .subject(memberId)
            .signWith(key)
            .expiration(java.util.Date.from(java.time.Instant.now().plusSeconds(1800)))
            .compact()
    }
}
