package com.quietchatter.gateway

import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

class ExpiredAuthTokenException(message: String) : RuntimeException(message)
class InvalidAuthTokenException(message: String) : RuntimeException(message)

@Service
class JwtTokenService(
    @Value("\${jwt.secret-key}") secretKeyString: String,
    private val redisTemplate: StringRedisTemplate
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(secretKeyString.toByteArray())
    private val jwtParser = Jwts.parser().verifyWith(key).build()

    val accessTokenLifeTime: Duration = Duration.ofMinutes(30)
    val refreshTokenLifeTime: Duration = Duration.ofDays(30)

    fun validateAndGetMemberId(token: String): String {
        try {
            val claims = jwtParser.parseSignedClaims(token).payload
            return claims.subject
        } catch (e: ExpiredJwtException) {
            throw ExpiredAuthTokenException("Token expired")
        } catch (e: JwtException) {
            throw InvalidAuthTokenException("Invalid token")
        } catch (e: IllegalArgumentException) {
            throw InvalidAuthTokenException("Invalid token")
        }
    }

    fun parseRefreshTokenAndGetTokenId(token: String): String {
        try {
            val claims = jwtParser.parseSignedClaims(token).payload
            return claims.id ?: throw InvalidAuthTokenException("No id in refresh token")
        } catch (e: ExpiredJwtException) {
            throw ExpiredAuthTokenException("Refresh token expired")
        } catch (e: Exception) {
            throw InvalidAuthTokenException("Invalid refresh token")
        }
    }

    fun findMemberIdByRefreshTokenId(tokenId: String): String? {
        return redisTemplate.opsForValue().get("refresh_token:$tokenId")
    }

    fun createNewAccessToken(memberId: String): String {
        val exp = Date.from(Instant.now().plus(accessTokenLifeTime))
        return Jwts.builder()
            .signWith(key)
            .subject(memberId)
            .expiration(exp)
            .compact()
    }

    fun createAndSaveRefreshToken(memberId: String): String {
        val tokenId = UUID.randomUUID().toString()
        val exp = Date.from(Instant.now().plus(refreshTokenLifeTime))
        val token = Jwts.builder()
            .signWith(key)
            .id(tokenId)
            .expiration(exp)
            .compact()
        
        redisTemplate.opsForValue().set("refresh_token:$tokenId", memberId, refreshTokenLifeTime)
        return token
    }

    fun deleteRefreshToken(tokenId: String): Boolean {
        return redisTemplate.delete("refresh_token:$tokenId")
    }
}
