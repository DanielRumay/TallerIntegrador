package com.example.tallerintegrador.controller.util;

import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Resuelve QUIEN esta haciendo la peticion, a partir del token y no de lo que diga el cuerpo.
 *
 * EL PROBLEMA QUE RESUELVE. Varios endpoints recibian el `usuarioId` en el cuerpo o como
 * parametro y lo usaban tal cual. Bastaba cambiar ese numero para guardar un intento a nombre
 * de otro alumno, o para leer sus datos. En una aplicacion normal seria un fallo de seguridad;
 * en esta es ademas un problema de VALIDEZ, porque todos los resultados de la tesis —kappa,
 * ganancia de aprendizaje, psicometria— se calculan sobre esos registros. Si no se puede
 * garantizar que cada intento pertenece a quien dice, los numeros no se sostienen.
 *
 * EL CRITERIO. Para las ESCRITURAS no se valida el id recibido: se IGNORA y se sustituye por
 * el del token. Validar deja la puerta de "y si algun dia alguien olvida validar"; ignorar
 * elimina la posibilidad. No se puede falsificar lo que no se lee.
 *
 * Para las LECTURAS, donde un docente o administrador si tiene motivos legitimos para
 * consultar a otro, se comprueba el rol.
 */
@Component
@RequiredArgsConstructor
public class UsuarioAutenticado {

    private final UserRepository userRepository;

    /** @return el usuario del token, o null si no hay sesion identificable. */
    public Usuario actual(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) return null;
        return userRepository.findByCorreo(authentication.getName()).orElse(null);
    }

    /** @return el id del usuario del token, o null. */
    public Long idActual(Authentication authentication) {
        Usuario u = actual(authentication);
        return u != null ? u.getId() : null;
    }

    /**
     * Docentes y administradores pueden consultar a cualquier alumno; un estudiante, solo a
     * si mismo. Misma regla que ya aplicaba RendimientoController.
     */
    public boolean puedeConsultarA(Long usuarioId, Authentication authentication) {
        if (authentication == null || usuarioId == null) return false;

        boolean esDocenteOAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> "TEACHER".equals(a.getAuthority()) || "ADMIN".equals(a.getAuthority()));
        if (esDocenteOAdmin) return true;

        Long propio = idActual(authentication);
        return propio != null && propio.equals(usuarioId);
    }
}
