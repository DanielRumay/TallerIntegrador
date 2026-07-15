package com.example.tallerintegrador.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    @org.springframework.beans.factory.annotation.Value("${app.rate-limit.max-requests:80}")
    private int maxRequestsPerMinute = 80;

    private final ConcurrentHashMap<String, List<Long>> ipRequestTimestamps = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // Check if target is an AI endpoint
        boolean isAiEndpoint = path.contains("/gemini")
                || path.contains("/agent-judge")
                || path.contains("/adaptive")
                || path.contains("/archivos");

        if (isAiEndpoint) {
            String clientIp = getClientIp(request);
            long now = System.currentTimeMillis();

            List<Long> timestamps = ipRequestTimestamps.computeIfAbsent(clientIp, k -> Collections.synchronizedList(new ArrayList<>()));

            synchronized (timestamps) {
                // Remove timestamps older than 1 minute
                timestamps.removeIf(timestamp -> now - timestamp > 60000);

                if (timestamps.size() >= maxRequestsPerMinute) {
                    response.setStatus(429); // HTTP Too Many Requests
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"error\":\"Límite de peticiones de IA excedido (máximo " + maxRequestsPerMinute + " por minuto). Por favor, intenta de nuevo más tarde.\"}");
                    return;
                }

                timestamps.add(now);
            }
        }

        filterChain.doFilter(request, response);
    }

    private String getClientIp(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null || xfHeader.isEmpty()) {
            return request.getRemoteAddr();
        }
        return xfHeader.split(",")[0].trim();
    }
}
