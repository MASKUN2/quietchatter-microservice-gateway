package com.quietchatter.gateway

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.net.URI

@Component
class AuthenticationFilter(
    private val jwtTokenService: JwtTokenService,
    private val objectMapper: ObjectMapper
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val wrappedRequest = GatewayHeaderRequestWrapper(request)

        if (request.requestURI.startsWith("/internal")) {
            errorResponse(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "내부 경로로의 접근이 금지되었습니다.")
            return
        }

        val accessToken = extractAccessToken(request)

        if (accessToken == null) {
            val refreshToken = extractRefreshToken(request)
            if (refreshToken != null) {
                handleRefreshToken(wrappedRequest, response, filterChain)
            } else {
                filterChain.doFilter(wrappedRequest, response)
            }
            return
        }

        try {
            val memberId = jwtTokenService.validateAndGetMemberId(accessToken)
            wrappedRequest.setMemberIdHeader(memberId)
            filterChain.doFilter(wrappedRequest, response)
        } catch (e: ExpiredAuthTokenException) {
            handleRefreshToken(wrappedRequest, response, filterChain)
        } catch (e: InvalidAuthTokenException) {
            errorResponse(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "유효하지 않은 토큰입니다.")
        }
    }

    private fun handleRefreshToken(
        request: GatewayHeaderRequestWrapper,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val refreshToken = extractRefreshToken(request)
        if (refreshToken == null) {
            filterChain.doFilter(request, response)
            return
        }

        try {
            val tokenId = jwtTokenService.parseRefreshTokenAndGetTokenId(refreshToken)
            val memberId = jwtTokenService.findMemberIdByRefreshTokenId(tokenId)

            if (memberId != null) {
                val newAccessToken = jwtTokenService.createNewAccessToken(memberId)
                jwtTokenService.deleteRefreshToken(tokenId)
                val newRefreshToken = jwtTokenService.createAndSaveRefreshToken(memberId)

                addTokenCookies(request, response, newAccessToken, newRefreshToken)
                request.setMemberIdHeader(memberId)
                filterChain.doFilter(request, response)
            } else {
                clearTokenCookies(request, response)
                filterChain.doFilter(request, response)
            }
        } catch (e: Exception) {
            log.error("Refresh token validation failed: {}", e.message)
            clearTokenCookies(request, response)
            filterChain.doFilter(request, response)
        }
    }

    private fun clearTokenCookies(request: HttpServletRequest, response: HttpServletResponse) {
        val isSecure = request.isSecure
        val accessCookieHeader = StringBuilder("ACCESS_TOKEN=; Path=/; HttpOnly; Max-Age=0")
        if (isSecure) accessCookieHeader.append("; Secure")
        accessCookieHeader.append("; SameSite=Lax")
        response.addHeader(HttpHeaders.SET_COOKIE, accessCookieHeader.toString())

        val refreshCookieHeader = StringBuilder("REFRESH_TOKEN=; Path=/; HttpOnly; Max-Age=0")
        if (isSecure) refreshCookieHeader.append("; Secure")
        refreshCookieHeader.append("; SameSite=Lax")
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookieHeader.toString())
    }

    private fun addTokenCookies(request: HttpServletRequest, response: HttpServletResponse, accessToken: String, refreshToken: String) {
        val isSecure = request.isSecure

        // Jakarta Servlet Cookie는 SameSite를 직접 지원하지 않으므로 헤더를 직접 설정하거나 
        // 응답 래퍼를 사용해야 하지만, 여기서는 가장 안정적인 Set-Cookie 헤더 직접 추가 방식을 사용합니다.
        
        val accessCookieHeader = StringBuilder("ACCESS_TOKEN=$accessToken; Path=/; HttpOnly; Max-Age=${jwtTokenService.accessTokenLifeTime.seconds}")
        if (isSecure) accessCookieHeader.append("; Secure")
        accessCookieHeader.append("; SameSite=Lax")
        response.addHeader(HttpHeaders.SET_COOKIE, accessCookieHeader.toString())

        val refreshCookieHeader = StringBuilder("REFRESH_TOKEN=$refreshToken; Path=/; HttpOnly; Max-Age=${jwtTokenService.refreshTokenLifeTime.seconds}")
        if (isSecure) refreshCookieHeader.append("; Secure")
        refreshCookieHeader.append("; SameSite=Lax")
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookieHeader.toString())
    }

    private fun extractAccessToken(request: HttpServletRequest): String? {
        val cookie = request.cookies?.find { it.name == "ACCESS_TOKEN" }?.value
        if (cookie != null) return cookie

        val header = request.getHeader(HttpHeaders.AUTHORIZATION)
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7)
        }
        return null
    }

    private fun extractRefreshToken(request: HttpServletRequest): String? {
        return request.cookies?.find { it.name == "REFRESH_TOKEN" }?.value
    }

    private fun errorResponse(
        response: HttpServletResponse,
        status: HttpStatus,
        code: String,
        message: String
    ) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = "UTF-8"

        val problemDetail = org.springframework.http.ProblemDetail.forStatusAndDetail(status, message).apply {
            title = code
            instance = URI.create(ServletUriComponentsBuilder.fromCurrentRequest().toUriString())
        }
        response.writer.write(objectMapper.writeValueAsString(problemDetail))
    }
}
