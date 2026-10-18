package com.example.tallerintegrador.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Component
public class JwtFilter extends OncePerRequestFilter {

    @Value("${jwt.secret}")
    private String secretKey;

    /**
     * Rol, colegio, permisos y estado vigentes, leídos de la base (ver ContextoUsuario). Es
     * opcional solo para las pruebas unitarias que construyen el filtro a mano; en la aplicación
     * siempre está, y entonces el rol del token se ignora.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.tallerintegrador.seguridad.ContextoUsuarioService contextoUsuarioService;

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // El token SOLO se acepta en la cabecera Authorization.
        //
        // Antes habia un respaldo que lo leia del parametro de consulta "token", porque
        // EventSource no admite cabeceras. Eso hacia que CUALQUIER endpoint se pudiera autenticar
        // con ?token=..., y el token quedaba en el historial del navegador, en los registros de
        // acceso del servidor y en cualquier proxy intermedio, en contra del RNF-10. El frontend
        // ya no usa EventSource: lee el flujo SSE con fetch, que si permite la cabecera.
        String authHeader = request.getHeader("Authorization");
        String token = null;

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        }

        if (token == null || token.trim().isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            // Leemos el token usando Keys.hmacShaKeyFor
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(io.jsonwebtoken.security.Keys.hmacShaKeyFor(secretKey.getBytes()))
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            String email = claims.getSubject();
            String rol = claims.get("rol", String.class);

            if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                if (contextoUsuarioService != null) {
                    var contexto = contextoUsuarioService.cargar(email);
                    if (contexto.isEmpty()) {
                        // Cuenta eliminada, bloqueada o de un colegio desactivado: la sesión ya no
                        // vale aunque el token siga sin caducar (RFS-08).
                        responderSesionRevocada(response);
                        return;
                    }
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            email, null, contexto.get().authorities());
                    authToken.setDetails(contexto.get());
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                } else {
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            email, null, Collections.singletonList(new SimpleGrantedAuthority(rol))
                    );
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (Exception e) {
            // Token inválido o expirado
            System.out.println("Token inválido: " + e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    private void responderSesionRevocada(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"La sesión ya no es válida. Inicia sesión de nuevo.\"}");
    }
}
