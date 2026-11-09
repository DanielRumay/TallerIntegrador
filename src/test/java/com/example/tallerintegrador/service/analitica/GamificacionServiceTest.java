package com.example.tallerintegrador.service.analitica;

import com.example.tallerintegrador.entidades.postgres.*;
import com.example.tallerintegrador.repository.IntentoRepository;
import com.example.tallerintegrador.repository.MatriculaRepository;
import com.example.tallerintegrador.repository.TurnoTutorSocraticoRepository;
import com.example.tallerintegrador.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GamificacionServiceTest {

    @Mock private ProgresoAlumnoService progresoAlumnoService;
    @Mock private IntentoRepository intentoRepository;
    @Mock private TurnoTutorSocraticoRepository turnoRepository;
    @Mock private MatriculaRepository matriculaRepository;
    @Mock private UserRepository userRepository;
    @Mock private MonedasService monedasService;

    private GamificacionService servicio;
    private Colegio colegio;
    private Curso curso;

    @BeforeEach
    void setUp() {
        servicio = new GamificacionService(progresoAlumnoService, intentoRepository, turnoRepository,
                matriculaRepository, userRepository, monedasService);
        colegio = new Colegio("Colegio de prueba");
        colegio.setId(1L);
        curso = new Curso();
        curso.setId(10L);
        curso.setNombre("Comunicación 2.° A");
        curso.setColegio(colegio);
    }

    private Usuario alumno(long id, String nombre) {
        Usuario u = new Usuario();
        u.setId(id);
        u.setNombre(nombre);
        u.setRol(Rol.STUDENT);
        u.setColegio(colegio);
        return u;
    }

    private Matricula matricula(Usuario u) {
        Matricula m = new Matricula();
        m.setUsuario(u);
        m.setCurso(curso);
        return m;
    }

    private Intento intento(Usuario u, double nota) {
        Intento i = new Intento();
        i.setUsuario(u);
        i.setNota(nota);
        i.setFecha(LocalDateTime.now());
        return i;
    }

    @Test
    void losNivelesSubenRapidoAlPrincipioYCuestanMasDespues() {
        assertEquals(1, GamificacionService.nivelDe(0));
        assertEquals(1, GamificacionService.nivelDe(49));
        assertEquals(2, GamificacionService.nivelDe(50));
        assertEquals(3, GamificacionService.nivelDe(150));
        assertEquals(4, GamificacionService.nivelDe(300));
        assertEquals(GamificacionService.xpParaNivel(4) - GamificacionService.xpParaNivel(3),
                GamificacionService.xpParaNivel(3) - GamificacionService.xpParaNivel(2) + 50);
    }

    @Test
    void losEmpatadosCompartenPuesto() {
        Usuario a = alumno(1, "Ana"), b = alumno(2, "Beto"), c = alumno(3, "Caro"), d = alumno(4, "Dani");
        var filas = GamificacionService.clasificar(List.of(a, b, c, d), Map.of(1L, 30, 2L, 50, 3L, 30), 4L);

        assertEquals(List.of(1, 2, 2, 4), filas.stream().map(GamificacionService.FilaLiga::posicion).toList());
        assertEquals(50, filas.get(0).xp());
        assertTrue(filas.get(3).esTu());
    }

    @Test
    void laLigaMuestraApodosNuncaNombresYSoloLaCabezaYTusVecinos() {
        List<Usuario> clase = new ArrayList<>();
        for (long id = 1; id <= 12; id++) clase.add(alumno(id, "Nombre Real " + id));
        Usuario yo = clase.get(11);
        when(matriculaRepository.findByUsuarioId(yo.getId())).thenReturn(List.of(matricula(yo)));
        when(matriculaRepository.findByCursoId(10L)).thenReturn(clase.stream().map(this::matricula).toList());
        // El alumno id k hizo k evaluaciones con nota 10: más id, más XP. "yo" (12) va primero.
        List<Intento> intentos = new ArrayList<>();
        for (Usuario u : clase) for (int k = 0; k < u.getId(); k++) intentos.add(intento(u, 10));
        when(intentoRepository.findByUsuarioIdInAndFechaGreaterThanEqualAndFechaLessThan(anyCollection(), any(), any())).thenReturn(intentos);
        when(turnoRepository.findByUsuarioIdInAndCerradoTrueAndFechaGreaterThanEqualAndFechaLessThan(anyCollection(), any(), any())).thenReturn(List.of());

        Map<String, Object> liga = servicio.ligaSemanal(yo.getId(), null).orElseThrow();

        assertEquals(1, liga.get("miPosicion"));
        assertNull(liga.get("xpParaSubir"));
        @SuppressWarnings("unchecked")
        var filas = (List<GamificacionService.FilaLiga>) liga.get("filas");
        assertEquals(5, filas.size());
        assertTrue(filas.stream().noneMatch(f -> f.apodo().startsWith("Nombre Real")));
    }

    @Test
    void desdeAbajoSeVenTusVecinosYCuantoTeFaltaParaSubir() {
        List<Usuario> clase = new ArrayList<>();
        for (long id = 1; id <= 12; id++) clase.add(alumno(id, "N" + id));
        Usuario yo = clase.get(2); // id 3: tercero desde abajo
        when(matriculaRepository.findByUsuarioId(yo.getId())).thenReturn(List.of(matricula(yo)));
        when(matriculaRepository.findByCursoId(10L)).thenReturn(clase.stream().map(this::matricula).toList());
        List<Intento> intentos = new ArrayList<>();
        for (Usuario u : clase) for (int k = 0; k < u.getId(); k++) intentos.add(intento(u, 10));
        when(intentoRepository.findByUsuarioIdInAndFechaGreaterThanEqualAndFechaLessThan(anyCollection(), any(), any())).thenReturn(intentos);
        when(turnoRepository.findByUsuarioIdInAndCerradoTrueAndFechaGreaterThanEqualAndFechaLessThan(anyCollection(), any(), any())).thenReturn(List.of());

        Map<String, Object> liga = servicio.ligaSemanal(yo.getId(), null).orElseThrow();

        assertEquals(10, liga.get("miPosicion"));
        // Cada evaluación con nota 10 vale 20 XP: al de arriba (id 4) le lleva 20.
        assertEquals(21, liga.get("xpParaSubir"));
        @SuppressWarnings("unchecked")
        var filas = (List<GamificacionService.FilaLiga>) liga.get("filas");
        assertEquals(List.of(1, 2, 3, 4, 5, 9, 10, 11),
                filas.stream().map(GamificacionService.FilaLiga::posicion).toList());
    }

    @Test
    void noSePuedeVerLaLigaDeUnCursoAjeno() {
        Usuario yo = alumno(1, "Ana");
        when(matriculaRepository.findByUsuarioId(1L)).thenReturn(List.of(matricula(yo)));

        assertTrue(servicio.ligaSemanal(1L, 99L).isEmpty());
        verify(matriculaRepository, never()).findByCursoId(99L);
    }

    @Test
    void soloSeAceptanApodosDeLaLista() {
        assertTrue(GamificacionService.apodoValido("Zorro Veloz"));
        assertFalse(GamificacionService.apodoValido("Profe Feo"));
        assertFalse(GamificacionService.apodoValido("Zorro"));
        assertThrows(IllegalArgumentException.class, () -> servicio.cambiarApodo(1L, "<script>"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void elPodioDeLaSemanaPasadaSePremiaConClaveUnica() {
        Usuario yo = alumno(1, "Ana"), otro = alumno(2, "Beto"), tercera = alumno(3, "Caro");
        when(matriculaRepository.findByUsuarioId(1L)).thenReturn(List.of(matricula(yo)));
        when(matriculaRepository.findByCursoId(10L)).thenReturn(List.of(matricula(yo), matricula(otro), matricula(tercera)));
        // Semana pasada: Ana 2 evaluaciones, Beto y Caro 1. Hace dos semanas solo practicó Ana:
        // sin competencia, esa semana no reparte podio.
        LocalDateTime lunesPasado = GamificacionService.inicioDeSemana(LocalDate.now()).minusWeeks(1);
        LocalDateTime haceDos = lunesPasado.minusWeeks(1);
        when(intentoRepository.findByUsuarioIdInAndFechaGreaterThanEqualAndFechaLessThan(anyCollection(), any(), any()))
                .thenAnswer(inv -> lunesPasado.equals(inv.getArgument(1))
                        ? List.of(intento(yo, 15), intento(yo, 15), intento(otro, 15), intento(tercera, 15))
                        : haceDos.equals(inv.getArgument(1)) ? List.of(intento(yo, 20)) : List.of());
        when(turnoRepository.findByUsuarioIdInAndCerradoTrueAndFechaGreaterThanEqualAndFechaLessThan(anyCollection(), any(), any()))
                .thenReturn(List.of());

        servicio.otorgarPodiosPendientes(1L);

        verify(monedasService).premiar(eq(1L), eq(50), contains("1.º puesto"), eq("podio:10:" + lunesPasado.toLocalDate()));
        verify(monedasService, times(1)).premiar(any(), anyInt(), any(), any());
    }

    @Test
    void laEtapaSaleDelNombreDelGrado() {
        assertEquals("SECUNDARIA", GamificacionService.etapaDe("2.° de Secundaria"));
        assertEquals("PRIMARIA", GamificacionService.etapaDe("5to primaria"));
        assertNull(GamificacionService.etapaDe("Grupo A"));
        assertNull(GamificacionService.etapaDe(null));
        assertEquals("clasica", GamificacionService.varianteInicial("5to Grado de Primaria"));
        assertEquals("nocturna", GamificacionService.varianteInicial("6to Grado de Primaria"));
        assertEquals("nocturna", GamificacionService.varianteInicial("2do Año de Secundaria"));
        assertEquals("nocturna", GamificacionService.varianteInicial("5to Año de Secundaria"));
        assertEquals("clasica", GamificacionService.varianteInicial("Primaria"));
        assertEquals("clasica", GamificacionService.varianteInicial(null));
    }

    @Test
    void laSemanaEmpiezaElLunes() {
        assertEquals(LocalDate.of(2026, 10, 26).atStartOfDay(),
                GamificacionService.inicioDeSemana(LocalDate.of(2026, 11, 1)));   // domingo
        assertEquals(LocalDate.of(2026, 11, 2).atStartOfDay(),
                GamificacionService.inicioDeSemana(LocalDate.of(2026, 11, 2)));   // lunes
    }
}
