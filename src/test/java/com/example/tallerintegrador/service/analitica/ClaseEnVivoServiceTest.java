package com.example.tallerintegrador.service.analitica;

import com.example.tallerintegrador.entidades.postgres.*;
import com.example.tallerintegrador.repository.MatriculaRepository;
import com.example.tallerintegrador.repository.SemanaRepository;
import com.example.tallerintegrador.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClaseEnVivoServiceTest {

    @Mock private SemanaRepository semanaRepository;
    @Mock private MatriculaRepository matriculaRepository;
    @Mock private UserRepository userRepository;

    private ClaseEnVivoService vivo;
    private static final Long DOCENTE = 50L;

    private final List<ClaseEnVivoService.PreguntaVivo> preguntas = List.of(
            new ClaseEnVivoService.PreguntaVivo("¿Núcleo del sujeto?", List.of("A) alumnos", "B) ganaron"), "A) alumnos"),
            new ClaseEnVivoService.PreguntaVivo("Sinónimo de veloz", List.of("Lento", "Rápido", "Pesado"), "B) Rápido"),
            new ClaseEnVivoService.PreguntaVivo("Sin correcta", List.of("x", "y"), "z"));

    @BeforeEach
    void setUp() {
        vivo = new ClaseEnVivoService(semanaRepository, matriculaRepository, userRepository);
        Usuario profe = new Usuario();
        profe.setId(DOCENTE);
        Curso curso = new Curso();
        curso.setId(10L);
        curso.setNombre("Comunicación");
        curso.setProfesor(profe);
        Semana semana = new Semana();
        semana.setId(1L);
        semana.setCurso(curso);
        semana.setNombreTema("El sujeto");
        lenient().when(semanaRepository.findById(1L)).thenReturn(Optional.of(semana));
    }

    private void alumno(long id) {
        Usuario u = new Usuario();
        u.setId(id);
        when(matriculaRepository.findByCursoIdAndUsuarioId(10L, id)).thenReturn(Optional.of(new Matricula()));
        when(userRepository.findById(id)).thenReturn(Optional.of(u));
    }

    @Test
    void soloUnDocenteDelCursoCreaLaClaseYSeDescartanPreguntasSinRespuestaValida() {
        assertThrows(SecurityException.class, () -> vivo.crear(99L, 1L, preguntas, 20));
        String codigo = vivo.crear(DOCENTE, 1L, preguntas, 20);
        assertTrue(codigo.matches("\\d{6}"));
        assertEquals(2, vivo.tablero(DOCENTE, codigo).get("total"), "la pregunta sin correcta no entra");
        assertThrows(SecurityException.class, () -> vivo.tablero(99L, codigo));
    }

    @Test
    void laCorrectaSeReconoceConOSinLetra() {
        assertEquals(1, ClaseEnVivoService.indiceDe(List.of("Lento", "Rápido"), "B) Rápido"));
        assertEquals(0, ClaseEnVivoService.indiceDe(List.of("A) alumnos", "B) ganaron"), "alumnos"));
        assertEquals(-1, ClaseEnVivoService.indiceDe(List.of("x"), null));
    }

    @Test
    void unAlumnoNoMatriculadoNoPuedeEntrarAunqueTengaElCodigo() {
        String codigo = vivo.crear(DOCENTE, 1L, preguntas, 20);
        when(matriculaRepository.findByCursoIdAndUsuarioId(10L, 7L)).thenReturn(Optional.empty());
        assertThrows(SecurityException.class, () -> vivo.unirse(7L, codigo));
        assertThrows(NoSuchElementException.class, () -> vivo.unirse(7L, "000000x"));
    }

    @Test
    void elDocenteNoVeLaRespuestaHastaCerrarYElAlumnoNuncaLaVeAntes() {
        String codigo = vivo.crear(DOCENTE, 1L, preguntas, 20);
        alumno(1L);
        vivo.unirse(1L, codigo);
        vivo.avanzar(DOCENTE, codigo);

        assertFalse(vivo.tablero(DOCENTE, codigo).containsKey("correcta"));
        assertFalse(vivo.estado(1L, codigo).containsKey("correcta"));
    }

    @Test
    void acertarRapidoDaMasPuntosYSeCierraCuandoRespondenTodos() {
        String codigo = vivo.crear(DOCENTE, 1L, preguntas, 20);
        alumno(1L);
        alumno(2L);
        vivo.unirse(1L, codigo);
        vivo.unirse(2L, codigo);
        vivo.avanzar(DOCENTE, codigo);

        vivo.responder(1L, codigo, 0);                       // correcta
        Map<String, Object> r2 = vivo.responder(2L, codigo, 1); // incorrecta: ya respondieron todos

        assertEquals("RESULTADO", r2.get("fase"));
        assertEquals(false, r2.get("acerte"));
        assertEquals(0, r2.get("ganancia"));
        Map<String, Object> r1 = vivo.estado(1L, codigo);
        assertEquals(true, r1.get("acerte"));
        assertTrue((Integer) r1.get("ganancia") > 900, "respondió al instante");
        assertEquals(1, r1.get("puesto"));
    }

    @Test
    void noSeRespondeDosVecesNiFueraDeTiempo() {
        String codigo = vivo.crear(DOCENTE, 1L, preguntas, 20);
        alumno(1L);
        alumno(2L);
        vivo.unirse(1L, codigo);
        vivo.unirse(2L, codigo);
        vivo.avanzar(DOCENTE, codigo);
        vivo.responder(1L, codigo, 1);
        vivo.responder(1L, codigo, 0); // el segundo intento se ignora
        assertEquals(1, vivo.estado(1L, codigo).get("miRespuesta"));

        vivo.avanzar(DOCENTE, codigo); // el docente cierra la pregunta
        assertThrows(IllegalStateException.class, () -> vivo.responder(2L, codigo, 0));
    }

    @Test
    void elPuntajeDependeDeLaRapidez() {
        assertEquals(1000, ClaseEnVivoService.puntaje(true, 0, 20));
        assertEquals(750, ClaseEnVivoService.puntaje(true, 10_000, 20));
        assertEquals(500, ClaseEnVivoService.puntaje(true, 30_000, 20));
        assertEquals(0, ClaseEnVivoService.puntaje(false, 0, 20));
    }

    @Test
    void alTerminarLasPreguntasLaClaseTermina() {
        String codigo = vivo.crear(DOCENTE, 1L, preguntas, 20);
        for (int i = 0; i < 4; i++) vivo.avanzar(DOCENTE, codigo); // P1, R1, P2, R2
        assertEquals("FIN", vivo.avanzar(DOCENTE, codigo).get("fase"));
        verify(userRepository, never()).save(any());
    }
}
