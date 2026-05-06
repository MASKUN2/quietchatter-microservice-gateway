package com.quietchatter.gateway

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseCookie
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

        when (val outcome = tokenRefreshClient.rotate(refreshToken)) {
            is RefreshOutcome.Success -> {
                outcome.setCookieHeaders.forEach { response.addHeader(HttpHeaders.SET_COOKIE, it) }
                request.setMemberIdHeader(outcome.memberId)
                filterChain.doFilter(request, response)
            }
            is RefreshOutcome.SessionExpired -> {
                clearTokenCookies(response)
                filterChain.doFilter(request, response)
            }
            is RefreshOutcome.Unavailable -> {
                filterChain.doFilter(request, response)
            }
        }
    }

    private fun clearTokenCookies(response: HttpServletResponse) {
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookieHeader("ACCESS_TOKEN", "", 0))
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookieHeader("REFRESH_TOKEN", "", 0))
    }

    private fun buildCookieHeader(name: String, value: String, maxAge: Long): String {
        val builder = ResponseCookie.from(name, value)
            .path("/")
            .httpOnly(true)
            .maxAge(maxAge)
            .secure(cookieProperties.secure)
            .sameSite(cookieProperties.sameSite)
        cookieProperties.domain?.let { builder.domain(it) }
        return builder.build().toString()
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
