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
    private val objectMapper: ObjectMapper,
    private val cookieProperties: GatewayCookieProperties,
    private val tokenRefreshClient: TokenRefreshClient
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val ACCESS_TOKEN_MAX_AGE_SECONDS = 30L * 60
        private const val REFRESH_TOKEN_MAX_AGE_SECONDS = 30L * 24 * 60 * 60
    }

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

        val result = tokenRefreshClient.rotate(refreshToken)
        if (result != null) {
            addTokenCookies(response, result.accessToken, result.refreshToken)
            request.setMemberIdHeader(result.memberId)
            filterChain.doFilter(request, response)
        } else {
            clearTokenCookies(response)
            filterChain.doFilter(request, response)
        }
    }

    private fun addTokenCookies(response: HttpServletResponse, accessToken: String, refreshToken: String) {
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookieHeader("ACCESS_TOKEN", accessToken, ACCESS_TOKEN_MAX_AGE_SECONDS))
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookieHeader("REFRESH_TOKEN", refreshToken, REFRESH_TOKEN_MAX_AGE_SECONDS))
    }

    private fun clearTokenCookies(response: HttpServletResponse) {
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookieHeader("ACCESS_TOKEN", "", 0))
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookieHeader("REFRESH_TOKEN", "", 0))
    }

    private fun buildCookieHeader(name: String, value: String, maxAge: Long): String {
        val sb = StringBuilder("$name=$value; Path=/; HttpOnly; Max-Age=$maxAge")
        if (cookieProperties.secure) sb.append("; Secure")
        cookieProperties.domain?.let { sb.append("; Domain=$it") }
        sb.append("; SameSite=${cookieProperties.sameSite}")
        return sb.toString()
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
