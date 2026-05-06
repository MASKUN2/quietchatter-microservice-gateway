package com.quietchatter.gateway

import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import javax.crypto.SecretKey

class ExpiredAuthTokenException(message: String) : RuntimeException(message)
class InvalidAuthTokenException(message: String) : RuntimeException(message)

@Service
class JwtTokenService(
    @Value("\${jwt.secret-key}") secretKeyString: String
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(secretKeyString.toByteArray(Charsets.UTF_8))
    private val jwtParser = Jwts.parser().verifyWith(key).build()

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
}
