package com.example.tallerintegrador.seguridad;

import com.example.tallerintegrador.entidades.postgres.Permiso;
import com.example.tallerintegrador.entidades.postgres.Rol;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

/** Simula en las pruebas lo que JwtFilter deja en el SecurityContext de cada petición. */
public final class SesionDePrueba {

    private SesionDePrueba() {}

    public static ContextoUsuario iniciar(Long id, String correo, Rol rol, Long colegioId, boolean principal, Set<Permiso> permisos) {
        ContextoUsuario ctx = new ContextoUsuario(id, correo, rol, colegioId, principal, permisos);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(correo, null, ctx.authorities());
        auth.setDetails(ctx);
        SecurityContextHolder.getContext().setAuthentication(auth);
        return ctx;
    }

    public static void cerrar() {
        SecurityContextHolder.clearContext();
    }
}
