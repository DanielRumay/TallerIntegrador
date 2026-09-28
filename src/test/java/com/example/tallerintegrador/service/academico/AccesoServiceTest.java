package com.example.tallerintegrador.service.academico;

import com.example.tallerintegrador.entidades.postgres.*;
import com.example.tallerintegrador.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Cambiar el id en la URL no puede dar acceso a lo ajeno. Estas pruebas fijan la diferencia
 * entre preguntar "¿eres docente?" (el rol) y "¿es tuyo?" (la pertenencia): lo segundo es lo
 * que impedia que un docente leyera el curso de otro o que un alumno entrara a una semana
 * apagada.
 */
class AccesoServiceTest {

    private UserRepository users;
    private CursoRepository cursos;
    private SemanaRepository semanas;
    private MatriculaRepository matriculas;
    private AccesoService acceso;

    private Usuario usuario(long id, Rol rol, String correo) {
        Usuario u = new Usuario();
        u.setId(id); u.setRol(rol); u.setCorreo(correo);
        return u;
    }

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        cursos = mock(CursoRepository.class);
        semanas = mock(SemanaRepository.class);
        matriculas = mock(MatriculaRepository.class);
        acceso = new AccesoService(users, cursos, semanas, matriculas);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    private void sesion(Usuario u) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u.getCorreo(), null,
                        Collections.singletonList(new SimpleGrantedAuthority(u.getRol().name()))));
        when(users.findByCorreo(u.getCorreo())).thenReturn(Optional.of(u));
    }

    private Curso curso(long id, Usuario titular) {
        Curso c = new Curso();
        c.setId(id); c.setProfesor(titular); c.setCoDocentes(java.util.Set.of());
        when(cursos.findById(id)).thenReturn(Optional.of(c));
        return c;
    }

    @Test
    @DisplayName("Un docente NO entra al curso de otro docente")
    void docenteAjenoRechazado() {
        Usuario mio = usuario(1L, Rol.TEACHER, "yo@colegio.edu.pe");
        sesion(mio);
        curso(10L, usuario(2L, Rol.TEACHER, "otro@colegio.edu.pe"));

        assertThatThrownBy(() -> acceso.exigirAccesoACurso(10L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("El docente titular sí entra a su curso")
    void docenteTitularEntra() {
        Usuario mio = usuario(1L, Rol.TEACHER, "yo@colegio.edu.pe");
        sesion(mio);
        curso(10L, mio);

        acceso.exigirAccesoACurso(10L); // no lanza
    }

    @Test
    @DisplayName("Un estudiante sin matricula NO entra al curso")
    void alumnoSinMatriculaRechazado() {
        Usuario alumno = usuario(5L, Rol.STUDENT, "alumno@colegio.edu.pe");
        sesion(alumno);
        curso(10L, usuario(2L, Rol.TEACHER, "prof@colegio.edu.pe"));
        when(matriculas.findByCursoIdAndUsuarioId(10L, 5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> acceso.exigirAccesoACurso(10L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("Un estudiante NO entra a una semana deshabilitada, aunque este matriculado")
    void semanaApagadaSeNiegaAlAlumno() {
        Usuario alumno = usuario(5L, Rol.STUDENT, "alumno@colegio.edu.pe");
        sesion(alumno);
        Curso c = curso(10L, usuario(2L, Rol.TEACHER, "prof@colegio.edu.pe"));
        when(matriculas.findByCursoIdAndUsuarioId(10L, 5L))
                .thenReturn(Optional.of(new Matricula()));

        Semana s = new Semana();
        s.setId(77L); s.setCurso(c); s.setHabilitada(false);
        when(semanas.findById(77L)).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> acceso.exigirAccesoASemana(77L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("El docente SÍ ve su semana deshabilitada: es quien la apaga")
    void semanaApagadaVisibleParaElDocente() {
        Usuario prof = usuario(2L, Rol.TEACHER, "prof@colegio.edu.pe");
        sesion(prof);
        Curso c = curso(10L, prof);

        Semana s = new Semana();
        s.setId(77L); s.setCurso(c); s.setHabilitada(false);
        when(semanas.findById(77L)).thenReturn(Optional.of(s));

        acceso.exigirAccesoASemana(77L); // no lanza
    }

    @Test
    @DisplayName("El administrador pasa siempre")
    void administradorPasa() {
        sesion(usuario(9L, Rol.ADMIN, "admin@colegio.edu.pe"));
        acceso.exigirAccesoACurso(999L);
        verify(cursos, never()).findById(any());
    }
}
