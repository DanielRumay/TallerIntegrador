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

        // Los preflight CORS no son trabajo: son protocolo. El navegador manda uno por cada
        // peticion con cabecera Authorization, asi que contarlos gastaba el presupuesto al doble
        // de velocidad sin que nadie hubiera pedido nada.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();

        // Check if target is an AI endpoint
        boolean isAiEndpoint = path.contains("/gemini")
                || path.contains("/agent-judge")
                || path.contains("/adaptive")
                || path.contains("/archivos");

        if (isAiEndpoint) {
            String clave = claveDeCuota(request);
            long now = System.currentTimeMillis();

            List<Long> timestamps = ipRequestTimestamps.computeIfAbsent(clave, k -> Collections.synchronizedList(new ArrayList<>()));

            synchronized (timestamps) {
                // Remove timestamps older than 1 minute
                timestamps.removeIf(timestamp -> now - timestamp > 60000);

                if (timestamps.size() >= maxRequestsPerMinute) {
                    response.setStatus(429); // HTTP Too Many Requests
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"error\":\"Límite de peticiones de IA excedido (maximo " + maxRequestsPerMinute + " por minuto y usuario). Por favor, intenta de nuevo más tarde.\"}");
                    return;
                }

                timestamps.add(now);
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Presupuesto POR USUARIO, no por IP.
     *
     * POR QUE CAMBIO. Contar por IP significa que en un colegio los veinte alumnos del aula
     * comparten un unico presupuesto, porque salen todos por el mismo router: el quinto en
     * empezar su evaluacion recibia un 429 sin haber hecho nada raro, y un solo alumno podia
     * bloquear a los demas. Con el correo del token cada uno tiene el suyo y el limite deja de
     * depender de cuanta gente haya conectada.
     *
     * La IP queda de reserva para lo no autenticado, que es donde si tiene sentido.
     */
    private String claveDeCuota(HttpServletRequest request) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null
                && !"anonymousUser".equals(auth.getName())) {
            return "u:" + auth.getName();
        }
        return "ip:" + getClientIp(request);
    }

    private String getClientIp(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null || xfHeader.isEmpty()) {
            return request.getRemoteAddr();
        }
        return xfHeader.split(",")[0].trim();
    }
}
