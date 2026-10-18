package com.example.tallerintegrador.service.academico;

import com.example.tallerintegrador.entidades.postgres.Curso;
import com.example.tallerintegrador.entidades.postgres.Rol;
import com.example.tallerintegrador.entidades.postgres.Semana;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.repository.CursoRepository;
import com.example.tallerintegrador.repository.MatriculaRepository;
import com.example.tallerintegrador.repository.SemanaRepository;
import com.example.tallerintegrador.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Autorizacion por PERTENENCIA del recurso, no solo por rol.
 *
 * POR QUE EXISTE. @PreAuthorize responde "¿eres docente?"; esto responde "¿es TUYO?". Sin la
 * segunda pregunta, un docente con token valido puede leer y modificar cursos de otro docente,
 * y un estudiante puede pedir semanas de un curso en el que no esta matriculado: basta con
 * cambiar el id en la URL. Cubre el RFS-03 del acta y el RNF-02.
 *
 * EL ADMINISTRADOR PASA SOLO EN SU COLEGIO. Administra su institución, no la plataforma: un
 * curso de otro colegio responde como inexistente (ver ColegioGuard).
 */
@Service
@RequiredArgsConstructor
public class AccesoService {

    private final UserRepository userRepository;
    private final CursoRepository cursoRepository;
    private final SemanaRepository semanaRepository;
    private final MatriculaRepository matriculaRepository;

    /** ¿La semana pertenece a un curso del colegio de la petición? Para el banco de preguntas del admin. */
    @Transactional(readOnly = true)
    public boolean semanaEnColegioActual(Long semanaId) {
        Long colegio = com.example.tallerintegrador.seguridad.ColegioGuard.colegioActual();
        return colegio != null && semanaRepository.findById(semanaId)
                .map(s -> s.getCurso() != null && colegio.equals(s.getCurso().colegioId()))
                .orElse(false);
    }

    /** ¿El usuario pertenece al colegio de la petición? Para docentes y administradores que consultan a un alumno. */
    @Transactional(readOnly = true)
    public boolean usuarioEnColegioActual(Long usuarioId) {
        Long colegio = com.example.tallerintegrador.seguridad.ColegioGuard.colegioActual();
        return colegio != null && usuarioId != null && userRepository.findById(usuarioId)
                .map(x -> colegio.equals(x.colegioId()))
                .orElse(false);
    }

    /** Usuario de la sesion, o vacio si la peticion no esta autenticada. */
    @Transactional(readOnly = true)
    public Usuario usuarioActual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) return null;
        return userRepository.findByCorreo(auth.getName()).orElse(null);
    }

    /**
     * Lanza AccessDeniedException si el usuario de la sesion no tiene relacion con el curso.
     *
     * Docente: debe ser el titular o figurar como co-docente. Estudiante: debe estar
     * matriculado. Se comprueban las dos cosas porque un curso tiene varios docentes.
     */
    @Transactional(readOnly = true)
    public void exigirAccesoACurso(Long cursoId) {
        Usuario u = usuarioActual();
        if (u == null) return;

        Curso curso = cursoRepository.findById(cursoId)
                .orElseThrow(() -> new AccessDeniedException("Curso no disponible."));

        if (u.getRol() == Rol.ADMIN) {
            // Mismo colegio o, para él, el curso no existe (no se revela que es de otro colegio).
            if (u.colegioId() == null || !u.colegioId().equals(curso.colegioId())) {
                throw new com.example.tallerintegrador.exception.RecursoNoEncontradoException("Curso no encontrado");
            }
            return;
        }

        if (u.getRol() == Rol.TEACHER) {
            boolean titular = curso.getProfesor() != null && curso.getProfesor().getId().equals(u.getId());
            boolean coDocente = curso.getCoDocentes() != null
                    && curso.getCoDocentes().stream().anyMatch(d -> d.getId().equals(u.getId()));
            if (!titular && !coDocente) {
                throw new AccessDeniedException("No dicta este curso.");
            }
            return;
        }

        if (matriculaRepository.findByCursoIdAndUsuarioId(cursoId, u.getId()).isEmpty()) {
            throw new AccessDeniedException("No esta matriculado en este curso.");
        }
    }

    /**
     * Igual que exigirAccesoACurso, pero partiendo de la semana. Ademas, al estudiante se le
     * niega la semana DESHABILITADA: el docente la apaga precisamente para que no la vea, y
     * confiar eso solo al frontend deja el dato accesible llamando a la API directamente.
     */
    @Transactional(readOnly = true)
    public void exigirAccesoASemana(Long semanaId) {
        Usuario u = usuarioActual();
        if (u == null) return;

        Semana semana = semanaRepository.findById(semanaId)
                .orElseThrow(() -> new AccessDeniedException("Semana no disponible."));
        if (semana.getCurso() != null) {
            exigirAccesoACurso(semana.getCurso().getId());
        } else if (u.getRol() == Rol.ADMIN) {
            throw new AccessDeniedException("Semana no disponible.");
        }
        if (u.getRol() == Rol.STUDENT && !semana.isHabilitada()) {
            throw new AccessDeniedException("Esta semana no esta habilitada.");
        }
    }
}
