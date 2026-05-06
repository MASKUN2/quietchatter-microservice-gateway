package com.quietchatter.gateway

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.contains
import org.mockito.Mockito.*
import org.springframework.http.HttpStatus
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

class AuthenticationFilterTest {

    private val jwtTokenService = mock(JwtTokenService::class.java)
    private val tokenRefreshClient = mock(TokenRefreshClient::class.java)
    private val objectMapper = ObjectMapper()
    private val cookieProperties = GatewayCookieProperties(domain = null, secure = false, sameSite = "Lax")
    private val filter = AuthenticationFilter(jwtTokenService, objectMapper, cookieProperties, tokenRefreshClient)
    private val request = mock(HttpServletRequest::class.java)
    private val response = mock(HttpServletResponse::class.java)
    private val filterChain = mock(FilterChain::class.java)

    @BeforeEach
    fun setUp() {
        val attributes = ServletRequestAttributes(request)
        RequestContextHolder.setRequestAttributes(attributes)
    }

    @AfterEach
    fun tearDown() {
        RequestContextHolder.resetRequestAttributes()
    }

    @Test
    fun `request without access token but with valid refresh token rotates via member service`() {
        // given
        `when`(request.requestURI).thenReturn("/api/members/me")
        val refreshCookie = Cookie("REFRESH_TOKEN", "valid-refresh-token")
        `when`(request.cookies).thenReturn(arrayOf(refreshCookie))
        `when`(request.getHeader(anyString())).thenReturn(null)

        val rotationResult = TokenRotationResult("new-access", "new-refresh", "member-123")
        `when`(tokenRefreshClient.rotate("valid-refresh-token")).thenReturn(rotationResult)

        // when
        filter.doFilter(request, response, filterChain)

        // then
        verify(tokenRefreshClient).rotate("valid-refresh-token")
        verify(filterChain).doFilter(any(GatewayHeaderRequestWrapper::class.java), eq(response))
    }

    @Test
    fun `request without token should pass through as anonymous`() {
        // given
        `when`(request.requestURI).thenReturn("/api/auth/me")
        `when`(request.cookies).thenReturn(null)
        `when`(request.getHeader(anyString())).thenReturn(null)

        // when
        filter.doFilter(request, response, filterChain)

        // then
        verify(filterChain).doFilter(any(), eq(response))
        verify(response, never()).status = HttpStatus.UNAUTHORIZED.value()
        verifyNoInteractions(tokenRefreshClient)
    }

    @Test
    fun `request with stale refresh token (member service returns null) should pass through as anonymous`() {
        // given - 리프레시 토큰 쿠키 있지만 멤버 서비스 로테이션 실패 (세션 만료)
        `when`(request.requestURI).thenReturn("/api/auth/me")
        val refreshCookie = Cookie("REFRESH_TOKEN", "stale-refresh-token")
        `when`(request.cookies).thenReturn(arrayOf(refreshCookie))
        `when`(request.getHeader(anyString())).thenReturn(null)

        `when`(tokenRefreshClient.rotate("stale-refresh-token")).thenReturn(null)

        // when
        filter.doFilter(request, response, filterChain)

        // then - 어나니머스로 통과, 쿠키 클리어
        verify(filterChain).doFilter(any(GatewayHeaderRequestWrapper::class.java), eq(response))
        verify(response, never()).status = HttpStatus.UNAUTHORIZED.value()
        verify(response, atLeastOnce()).addHeader(eq("Set-Cookie"), contains("Max-Age=0"))
    }

    @Test
    fun `request with expired access token falls back to token refresh via member service`() {
        // given
        `when`(request.requestURI).thenReturn("/api/auth/me")
        val accessCookie = Cookie("ACCESS_TOKEN", "expired-access-token")
        val refreshCookie = Cookie("REFRESH_TOKEN", "valid-refresh-token")
        `when`(request.cookies).thenReturn(arrayOf(accessCookie, refreshCookie))

        `when`(jwtTokenService.validateAndGetMemberId("expired-access-token"))
            .thenThrow(ExpiredAuthTokenException("Token expired"))
        val rotationResult = TokenRotationResult("new-access", "new-refresh", "member-123")
        `when`(tokenRefreshClient.rotate("valid-refresh-token")).thenReturn(rotationResult)

        // when
        filter.doFilter(request, response, filterChain)

        // then
        verify(tokenRefreshClient).rotate("valid-refresh-token")
        verify(filterChain).doFilter(any(GatewayHeaderRequestWrapper::class.java), eq(response))
    }

    @Test
    fun `internal path should return forbidden`() {
        // given
        `when`(request.requestURI).thenReturn("/internal/api/members/some-id")
        val writer = mock(java.io.PrintWriter::class.java)
        `when`(response.writer).thenReturn(writer)

        // when
        filter.doFilter(request, response, filterChain)

        // then
        verify(response).status = HttpStatus.FORBIDDEN.value()
        verify(filterChain, never()).doFilter(any(), any())
    }
}
