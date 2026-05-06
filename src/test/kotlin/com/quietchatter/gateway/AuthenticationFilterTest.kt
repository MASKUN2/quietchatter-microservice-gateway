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
import java.time.Duration

class AuthenticationFilterTest {

    private val jwtTokenService = mock(JwtTokenService::class.java)
    private val objectMapper = ObjectMapper()
    private val filter = AuthenticationFilter(jwtTokenService, objectMapper)
    private val request = mock(HttpServletRequest::class.java)
    private val response = mock(HttpServletResponse::class.java)
    private val filterChain = mock(FilterChain::class.java)

    @BeforeEach
    fun setUp() {
        val attributes = ServletRequestAttributes(request)
        RequestContextHolder.setRequestAttributes(attributes)
        `when`(jwtTokenService.accessTokenLifeTime).thenReturn(Duration.ofMinutes(30))
        `when`(jwtTokenService.refreshTokenLifeTime).thenReturn(Duration.ofDays(30))
    }

    @AfterEach
    fun tearDown() {
        RequestContextHolder.resetRequestAttributes()
    }

    @Test
    fun `request without access token but with valid refresh token should attempt to refresh`() {
        // given
        `when`(request.requestURI).thenReturn("/api/members/me")
        val refreshCookie = Cookie("REFRESH_TOKEN", "valid-refresh-token")
        `when`(request.cookies).thenReturn(arrayOf(refreshCookie))
        `when`(request.getHeader(anyString())).thenReturn(null)

        val tokenId = "some-token-id"
        val memberId = "member-123"
        `when`(jwtTokenService.parseRefreshTokenAndGetTokenId("valid-refresh-token")).thenReturn(tokenId)
        `when`(jwtTokenService.findMemberIdByRefreshTokenId(tokenId)).thenReturn(memberId)
        `when`(jwtTokenService.createNewAccessToken(memberId)).thenReturn("new-access-token")
        `when`(jwtTokenService.createAndSaveRefreshToken(memberId)).thenReturn("new-refresh-token")

        // when
        filter.doFilter(request, response, filterChain)

        // then
        verify(jwtTokenService).parseRefreshTokenAndGetTokenId("valid-refresh-token")
        verify(jwtTokenService).findMemberIdByRefreshTokenId(tokenId)
        verify(filterChain).doFilter(any(GatewayHeaderRequestWrapper::class.java), eq(response))
    }

    @Test
    fun `request without token should pass through without X-Member-Id header`() {
        // given
        `when`(request.requestURI).thenReturn("/api/auth/logout")
        `when`(request.cookies).thenReturn(null)
        `when`(request.getHeader(anyString())).thenReturn(null)

        // when
        filter.doFilter(request, response, filterChain)

        // then
        verify(filterChain).doFilter(any(), eq(response))
        verify(response, never()).status = HttpStatus.UNAUTHORIZED.value()
    }

    @Test
    fun `request with only stale refresh token cookie (not in Redis) should pass through as anonymous and clear cookies`() {
        // given - 리프레시 토큰 쿠키만 있고 Redis에는 항목 없음 (만료된 세션)
        `when`(request.requestURI).thenReturn("/api/auth/me")
        val refreshCookie = Cookie("REFRESH_TOKEN", "stale-refresh-token")
        `when`(request.cookies).thenReturn(arrayOf(refreshCookie))
        `when`(request.getHeader(anyString())).thenReturn(null)
        `when`(request.isSecure).thenReturn(false)

        val tokenId = "stale-token-id"
        `when`(jwtTokenService.parseRefreshTokenAndGetTokenId("stale-refresh-token")).thenReturn(tokenId)
        `when`(jwtTokenService.findMemberIdByRefreshTokenId(tokenId)).thenReturn(null)

        // when
        filter.doFilter(request, response, filterChain)

        // then - 어나니머스로 통과, 401 반환 없음
        verify(filterChain).doFilter(any(GatewayHeaderRequestWrapper::class.java), eq(response))
        verify(response, never()).status = HttpStatus.UNAUTHORIZED.value()
        // 만료 쿠키 클리어 확인
        verify(response, atLeastOnce()).addHeader(eq("Set-Cookie"), contains("Max-Age=0"))
    }

    @Test
    fun `request with expired access token and stale refresh token should pass through as anonymous`() {
        // given - 액세스 토큰 만료 + 리프레시 토큰도 Redis에 없음
        `when`(request.requestURI).thenReturn("/api/auth/me")
        val accessCookie = Cookie("ACCESS_TOKEN", "expired-access-token")
        val refreshCookie = Cookie("REFRESH_TOKEN", "stale-refresh-token")
        `when`(request.cookies).thenReturn(arrayOf(accessCookie, refreshCookie))
        `when`(request.isSecure).thenReturn(false)

        `when`(jwtTokenService.validateAndGetMemberId("expired-access-token"))
            .thenThrow(ExpiredAuthTokenException("Token expired"))
        val tokenId = "stale-token-id"
        `when`(jwtTokenService.parseRefreshTokenAndGetTokenId("stale-refresh-token")).thenReturn(tokenId)
        `when`(jwtTokenService.findMemberIdByRefreshTokenId(tokenId)).thenReturn(null)

        // when
        filter.doFilter(request, response, filterChain)

        // then - 어나니머스로 통과, 401 반환 없음
        verify(filterChain).doFilter(any(GatewayHeaderRequestWrapper::class.java), eq(response))
        verify(response, never()).status = HttpStatus.UNAUTHORIZED.value()
    }

    @Test
    fun `request with invalid refresh token should pass through as anonymous and clear cookies`() {
        // given - 리프레시 토큰 파싱 자체가 실패 (위변조 또는 손상)
        `when`(request.requestURI).thenReturn("/api/auth/me")
        val refreshCookie = Cookie("REFRESH_TOKEN", "invalid-refresh-token")
        `when`(request.cookies).thenReturn(arrayOf(refreshCookie))
        `when`(request.getHeader(anyString())).thenReturn(null)
        `when`(request.isSecure).thenReturn(false)

        `when`(jwtTokenService.parseRefreshTokenAndGetTokenId("invalid-refresh-token"))
            .thenThrow(InvalidAuthTokenException("Invalid token"))

        // when
        filter.doFilter(request, response, filterChain)

        // then - 어나니머스로 통과, 401 반환 없음
        verify(filterChain).doFilter(any(GatewayHeaderRequestWrapper::class.java), eq(response))
        verify(response, never()).status = HttpStatus.UNAUTHORIZED.value()
        verify(response, atLeastOnce()).addHeader(eq("Set-Cookie"), contains("Max-Age=0"))
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
