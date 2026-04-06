package com.quietchatter.gateway

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import java.util.*

/**
 * 서비스 식별자인 X-Member-Id 헤더를 요청에 추가하거나 수정하기 위한 래퍼 클래스입니다.
 */
class GatewayHeaderRequestWrapper(request: HttpServletRequest) : HttpServletRequestWrapper(request) {
    private val customHeaders = mutableMapOf<String, String>()

    fun setMemberIdHeader(memberId: String) {
        customHeaders["X-Member-Id"] = memberId
    }

    override fun getHeader(name: String): String? {
        if (name.equals("X-Member-Id", ignoreCase = true)) {
            return customHeaders["X-Member-Id"]
        }
        return super.getHeader(name)
    }

    override fun getHeaderNames(): Enumeration<String> {
        val names = Collections.list(super.getHeaderNames()).toMutableList()
        names.removeIf { it.equals("X-Member-Id", ignoreCase = true) }
        names.addAll(customHeaders.keys)
        return Collections.enumeration(names)
    }
    
    override fun getHeaders(name: String): Enumeration<String> {
        if (name.equals("X-Member-Id", ignoreCase = true)) {
            val value = customHeaders["X-Member-Id"]
            return if (value != null) Collections.enumeration(listOf(value)) else Collections.emptyEnumeration()
        }
        return super.getHeaders(name)
    }
}
