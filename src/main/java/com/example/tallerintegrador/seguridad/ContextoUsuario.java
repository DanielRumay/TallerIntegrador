package com.example.tallerintegrador.seguridad;

import com.example.tallerintegrador.entidades.postgres.Permiso;
import com.example.tallerintegrador.entidades.postgres.Rol;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Quién hace la petición, leído de la base de datos y no del token.
 *
 * POR QUE NO DEL TOKEN. El JWT vive 24 horas: si los permisos o el bloqueo salieran de él, quitar
 * un permiso o bloquear una cuenta no tendría efecto hasta que el token caducara. Aquí el token
 * solo prueba la identidad; el rol, el colegio, los permisos y el estado se consultan en cada
 * petición (con un caché corto que se invalida al cambiarlos, ver ContextoUsuarioService).
 *
 * Es inmutable y no contiene entidades: puede guardarse en caché y en la Authentication sin
 * arrastrar sesiones de Hibernate.
 */
public record ContextoUsuario(Long id, String correo, Rol rol, Long colegioId,
                              boolean adminPrincipal, Set<Permiso> permisos) {

    public ContextoUsuario {
        permisos = permisos == null ? Set.of() : Set.copyOf(permisos);
    }

    public boolean esSuperadmin() {
        return rol == Rol.SUPERADMIN;
    }

    public boolean tiene(Permiso permiso) {
        return rol == Rol.ADMIN && permisos.contains(permiso);
    }

    /** El rol como authority (compatibilidad con los hasAuthority('TEACHER') existentes) y un PERM_ por permiso. */
    public List<GrantedAuthority> authorities() {
        List<GrantedAuthority> lista = new ArrayList<>();
        lista.add(new SimpleGrantedAuthority(rol.name()));
        if (rol == Rol.ADMIN) {
            permisos.forEach(p -> lista.add(new SimpleGrantedAuthority(p.authority())));
        }
        return lista;
    }

    /** Contexto de la petición en curso, o null si no está autenticada (o en hilos sin contexto). */
    public static ContextoUsuario actual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getDetails() instanceof ContextoUsuario ctx) return ctx;
        return null;
    }
}
