package com.example.tallerintegrador.seguridad;

import com.example.tallerintegrador.entidades.postgres.Curso;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.exception.RecursoNoEncontradoException;

import java.util.Objects;

/**
 * Verifica que un recurso buscado por id pertenezca al colegio de quien lo pide.
 *
 * POR QUE HACE FALTA ADEMAS DEL FILTRO DE HIBERNATE. El filtro restringe las consultas JPQL, pero
 * no findById ni las consultas nativas: un administrador que conoce el id de un usuario de otro
 * colegio lo obtendría igual. Este guardia cubre ese camino.
 *
 * POR QUE 404 Y NO 403. Un 403 confirmaría que el recurso existe en otro colegio. Se responde
 * igual que si el id no existiera.
 */
public final class ColegioGuard {

    private ColegioGuard() {}

    /** Colegio de la petición en curso; null si no hay usuario (tareas del sistema) o es SUPERADMIN. */
    public static Long colegioActual() {
        ContextoUsuario ctx = ContextoUsuario.actual();
        return ctx == null ? null : ctx.colegioId();
    }

    /** Colegio de la petición; falla si no hay uno (p. ej. un SUPERADMIN pidiendo datos académicos). */
    public static Long colegioObligatorio() {
        Long id = colegioActual();
        if (id == null) throw new RecursoNoEncontradoException("Recurso no encontrado");
        return id;
    }

    public static void exigir(Usuario usuario) {
        exigirColegio(usuario == null ? null : usuario.colegioId(), "Usuario no encontrado");
    }

    public static void exigir(Curso curso) {
        exigirColegio(curso == null ? null : curso.colegioId(), "Curso no encontrado");
    }

    private static void exigirColegio(Long colegioDelRecurso, String mensaje) {
        ContextoUsuario ctx = ContextoUsuario.actual();
        if (ctx == null) return; // tarea del sistema (migración, respaldo programado): sin restricción
        if (ctx.colegioId() == null || !Objects.equals(ctx.colegioId(), colegioDelRecurso)) {
            throw new RecursoNoEncontradoException(mensaje);
        }
    }
}
