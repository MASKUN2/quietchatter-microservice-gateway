package com.quietchatter.gateway

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.filter.CorsFilter

@Configuration
class CorsConfig(private val appCorsProperties: AppCorsProperties) {

    @Bean
    fun corsFilter(): CorsFilter {
        val config = CorsConfiguration()
        config.allowedOrigins = appCorsProperties.allowedOrigins
        config.allowedMethods = appCorsProperties.allowedMethods
        config.allowedHeaders = appCorsProperties.allowedHeaders
        config.allowCredentials = appCorsProperties.allowCredentials
        config.maxAge = 3600L

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", config)
        return CorsFilter(source)
    }
}
