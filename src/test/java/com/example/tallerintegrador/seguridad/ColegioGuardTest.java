package com.example.tallerintegrador.seguridad;

import com.example.tallerintegrador.entidades.postgres.Colegio;
import com.example.tallerintegrador.entidades.postgres.Curso;
import com.example.tallerintegrador.entidades.postgres.Rol;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.exception.RecursoNoEncontradoException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ColegioGuardTest {

    @AfterEach
    void limpiar() {
        SesionDePrueba.cerrar();
    }

    private static Usuario usuarioDe(long colegioId) {
        Colegio c = new Colegio("C" + colegioId);
        c.setId(colegioId);
        Usuario u = new Usuario();
        u.setColegio(c);
        return u;
    }

    @Test
    @DisplayName("Mismo colegio: pasa")
    void mismoColegio() {
        SesionDePrueba.iniciar(1L, "adm@a.pe", Rol.ADMIN, 1L, true, Set.of());
        assertThatCode(() -> ColegioGuard.exigir(usuarioDe(1L))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Otro colegio: responde como inexistente (404), no 403")
    void otroColegio() {
        SesionDePrueba.iniciar(1L, "adm@a.pe", Rol.ADMIN, 1L, true, Set.of());
        assertThatThrownBy(() -> ColegioGuard.exigir(usuarioDe(2L)))
                .isInstanceOf(RecursoNoEncontradoException.class);

        Curso curso = new Curso();
        curso.setColegio(usuarioDe(2L).getColegio());
        assertThatThrownBy(() -> ColegioGuard.exigir(curso)).isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    @DisplayName("El SUPERADMIN no accede a datos de ningún colegio por esta vía")
    void superadmin() {
        SesionDePrueba.iniciar(1L, "sa@p.pe", Rol.SUPERADMIN, null, false, Set.of());
        assertThatThrownBy(() -> ColegioGuard.exigir(usuarioDe(1L))).isInstanceOf(RecursoNoEncontradoException.class);
        assertThatThrownBy(ColegioGuard::colegioObligatorio).isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    @DisplayName("Sin usuario en la petición (tareas internas) no restringe")
    void sistema() {
        assertThatCode(() -> ColegioGuard.exigir(usuarioDe(7L))).doesNotThrowAnyException();
    }
}
