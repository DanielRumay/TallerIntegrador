package com.example.tallerintegrador.service.analitica;

import com.example.tallerintegrador.entidades.postgres.*;
import com.example.tallerintegrador.repository.MatriculaRepository;
import com.example.tallerintegrador.repository.RespuestaUsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JefeSemanalServiceTest {

    @Mock private MatriculaRepository matriculaRepository;
    @Mock private RespuestaUsuarioRepository respuestaRepository;
    @Mock private MonedasService monedasService;

    private JefeSemanalService servicio;
    private Curso curso;
    private final List<Matricula> clase = new ArrayList<>();

    @BeforeEach
    void setUp() {
        servicio = new JefeSemanalService(matriculaRepository, respuestaRepository, monedasService);
        Colegio colegio = new Colegio("Colegio de prueba");
        colegio.setId(1L);
        curso = new Curso();
        curso.setId(10L);
        curso.setNombre("Comunicación");
        curso.setColegio(colegio);
        for (long id = 1; id <= 5; id++) {
            Usuario u = new Usuario();
            u.setId(id);
            u.setRol(Rol.STUDENT);
            u.setColegio(colegio);
            Matricula m = new Matricula();
            m.setUsuario(u);
            m.setCurso(curso);
            clase.add(m);
        }
    }

    @Test
    void laVidaEscalaConElSalonYTieneMinimo() {
        assertEquals(20, JefeSemanalService.vidaPara(2));
        assertEquals(180, JefeSemanalService.vidaPara(30));
    }

    @Test
    void cadaSemanaLlegaUnJefeDistinto() {
        LocalDate lunes = LocalDate.of(2026, 11, 2);
        assertNotEquals(JefeSemanalService.jefeDe(lunes), JefeSemanalService.jefeDe(lunes.plusWeeks(1)));
    }

    @Test
    void losAciertosDeTodosSumanYSoloCuentanLosDelSalon() {
        when(matriculaRepository.findByCursoId(10L)).thenReturn(clase);
        // 5 alumnos: vida 30. Aciertos: 12 + 10 + 9, más 50 de alguien que NO es del salón.
        when(respuestaRepository.aciertosPorAlumnoEnCurso(eq(10L), any(), any()))
                .thenReturn(List.of(new Object[]{1L, 12L}, new Object[]{2L, 10L}, new Object[]{3L, 9L}, new Object[]{99L, 50L}));

        var b = servicio.batalla(clase.get(0), LocalDateTime.now());

        assertEquals(30, b.vida());
        assertEquals(31, b.danio());
        assertTrue(b.derrotado());
        assertFalse(b.aportes().containsKey(99L));
    }

    @Test
    void elCofreEsSoloParaQuienAportoYSeCobraConClaveUnica() {
        when(matriculaRepository.findByUsuarioId(1L)).thenReturn(List.of(clase.get(0)));
        when(matriculaRepository.findByCursoId(10L)).thenReturn(clase);
        LocalDateTime lunes = GamificacionService.inicioDeSemana(LocalDate.now());
        when(respuestaRepository.aciertosPorAlumnoEnCurso(eq(10L), any(), any())).thenAnswer(inv ->
                lunes.equals(inv.getArgument(1))
                        ? List.of(new Object[]{1L, 20L}, new Object[]{2L, 15L})
                        : List.of());

        Map<String, Object> estado = servicio.estado(1L, null).orElseThrow();

        assertEquals(true, estado.get("derrotado"));
        assertEquals(30L, estado.get("danio"), "el daño mostrado no pasa de la vida");
        verify(monedasService).premiar(eq(1L), eq(30), contains("derrotaron"), eq("jefe:10:" + lunes.toLocalDate()));
    }

    @Test
    void sinAporteNoHayCofre() {
        when(matriculaRepository.findByUsuarioId(5L)).thenReturn(List.of(clase.get(4)));
        when(matriculaRepository.findByCursoId(10L)).thenReturn(clase);
        when(respuestaRepository.aciertosPorAlumnoEnCurso(eq(10L), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{1L, 40L}));

        servicio.estado(5L, null);

        verify(monedasService, never()).premiar(any(), anyInt(), any(), any());
    }
}
