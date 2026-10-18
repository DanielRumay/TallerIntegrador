package com.example.tallerintegrador.seguridad;

import com.example.tallerintegrador.entidades.postgres.Usuario;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.hibernate.Session;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Activa el filtro de Hibernate "filtroColegio" en la sesión de la petición, de modo que las
 * consultas JPQL de Usuario y Curso solo devuelvan filas del colegio del usuario.
 *
 * ORDEN. Debe correr DESPUÉS de OpenEntityManagerInView, que es quien abre la sesión de la
 * petición; si corriera antes, el filtro se activaría en una sesión descartable. Por eso se
 * registra con la menor precedencia. Las transacciones de los servicios reutilizan esa misma
 * sesión, así que el filtro sigue activo dentro de ellas.
 *
 * El SUPERADMIN no tiene colegio: no se activa el filtro, pero tampoco tiene acceso a ningún
 * endpoint académico.
 */
@Component
public class FiltroColegioInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        ContextoUsuario ctx = ContextoUsuario.actual();
        if (ctx != null && ctx.colegioId() != null) {
            entityManager.unwrap(Session.class)
                    .enableFilter(Usuario.FILTRO_COLEGIO)
                    .setParameter("colegioId", ctx.colegioId());
        }
        return true;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).order(Ordered.LOWEST_PRECEDENCE);
    }
}
