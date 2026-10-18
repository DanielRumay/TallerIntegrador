package com.example.tallerintegrador.service.academico;

import com.example.tallerintegrador.entidades.postgres.*;
import com.example.tallerintegrador.repository.IntentoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * RFS-03: un docente solo ve los intentos de los cursos que dicta. Antes /intentos/todos
 * devolvía los de toda la plataforma a cualquier docente.
 */
@ExtendWith(MockitoExtension.class)
class IntentosPorPertenenciaTest {

    @Mock IntentoRepository intentoRepository;
    @InjectMocks IntentoService intentoService;

    private static Usuario usuario(long id, Rol rol) {
        Usuario u = new Usuario();
        u.setId(id);
        u.setRol(rol);
        u.setNombre("Usuario " + id);
        u.setCorreo("u" + id + "@demo.pe");
        return u;
    }

    private static Intento intento(long id, Curso curso, Usuario alumno) {
        Semana semana = new Semana();
        semana.setCurso(curso);
        semana.setNumSem("Semana 1");
        Intento i = new Intento();
        i.setId(id);
        i.setSemana(semana);
        i.setUsuario(alumno);
        i.setNota(15.0);
        i.setFecha(LocalDateTime.of(2026, 10, 16, 10, 0));
        i.setTipoEvaluacion(TipoEvaluacion.DIAGNOSTICA);
        return i;
    }

    private final Usuario docenteA = usuario(1, Rol.TEACHER);
    private final Usuario docenteB = usuario(2, Rol.TEACHER);
    private final Usuario coDocente = usuario(3, Rol.TEACHER);
    private final Usuario alumno = usuario(10, Rol.STUDENT);

    private List<Intento> intentosDeDosCursos() {
        Curso cursoA = new Curso();
        cursoA.setId(100L);
        cursoA.setNombre("Curso A");
        cursoA.setProfesor(docenteA);
        cursoA.getCoDocentes().add(coDocente);
        Curso cursoB = new Curso();
        cursoB.setId(200L);
        cursoB.setNombre("Curso B");
        cursoB.setProfesor(docenteB);
        return List.of(intento(1, cursoA, alumno), intento(2, cursoB, alumno));
    }

    private List<Object> cursosVistos(Usuario solicitante) {
        when(intentoRepository.findAll()).thenReturn(intentosDeDosCursos());
        return intentoService.obtenerTodosLosIntentos(solicitante).stream()
                .map((Map<String, Object> fila) -> fila.get("curso")).toList();
    }

    @Test
    void elDocenteSoloVeLosIntentosDeSuCurso() {
        assertThat(cursosVistos(docenteA)).containsExactly("Curso A");
    }

    @Test
    void elCoDocenteTambienVeLosDelCursoQueComparte() {
        assertThat(cursosVistos(coDocente)).containsExactly("Curso A");
    }

    @Test
    void elAdministradorVeTodos() {
        assertThat(cursosVistos(usuario(99, Rol.ADMIN))).containsExactlyInAnyOrder("Curso A", "Curso B");
    }

    @Test
    void sinUsuarioDeSesionNoSeDevuelveNada() {
        assertThat(cursosVistos(null)).isEmpty();
    }
}
