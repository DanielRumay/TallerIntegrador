package com.example.tallerintegrador.service.analitica;

import com.example.tallerintegrador.entidades.postgres.*;
import com.example.tallerintegrador.repository.IntentoRepository;
import com.example.tallerintegrador.repository.MatriculaRepository;
import com.example.tallerintegrador.repository.MovimientoMonedasRepository;
import com.example.tallerintegrador.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PerfilServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private MatriculaRepository matriculaRepository;
    @Mock private ProgresoAlumnoService progresoAlumnoService;
    @Mock private IntentoRepository intentoRepository;
    @Mock private MovimientoMonedasRepository movimientoRepository;
    @Mock private TiendaService tiendaService;

    private PerfilService servicio;
    private Colegio colegio;

    @BeforeEach
    void setUp() {
        servicio = new PerfilService(userRepository, matriculaRepository, progresoAlumnoService,
                intentoRepository, movimientoRepository, tiendaService);
        colegio = new Colegio("Colegio de prueba");
        colegio.setId(1L);
    }

    private Usuario alumno(long id) {
        Usuario u = new Usuario();
        u.setId(id);
        u.setNombre("Nombre Real " + id);
        u.setRol(Rol.STUDENT);
        u.setColegio(colegio);
        lenient().when(userRepository.findById(id)).thenReturn(Optional.of(u));
        return u;
    }

    private void matricular(Usuario u, long cursoId) {
        Curso c = new Curso();
        c.setId(cursoId);
        c.setColegio(colegio);
        Matricula m = new Matricula();
        m.setUsuario(u);
        m.setCurso(c);
        lenient().when(matriculaRepository.findByUsuarioId(u.getId())).thenReturn(List.of(m));
    }

    private void conProgreso(long id, int racha) {
        Map<String, Object> p = new HashMap<>();
        p.put("puntos", 160);
        p.put("racha", racha);
        p.put("rango", "Explorador");
        p.put("rangoEmoji", "🧭");
        p.put("conceptosDominados", 3);
        p.put("evaluacionesCompletadas", 8);
        p.put("mejorNota", 19.0);
        p.put("ultimaNota", 11.0);
        when(progresoAlumnoService.progresoDe(id)).thenReturn(p);
    }

    @Test
    void unCompaneroVeApodoNivelRachaYSuAriaPeroNuncaNombreNiNotas() {
        Usuario yo = alumno(1), otro = alumno(2);
        otro.setAriaVariante("perro");
        otro.setAriaEquipado("gorro");
        matricular(yo, 10);
        matricular(otro, 10);
        conProgreso(2, 8);
        when(intentoRepository.findByUsuarioIdOrderByFechaDesc(2L)).thenReturn(List.of());
        when(tiendaService.accesoriosDe(2L)).thenReturn(Set.of("gorro", "bufanda"));
        MovimientoMonedas podio = new MovimientoMonedas();
        podio.setMotivo("1.º puesto en la liga de Comunicación");
        when(movimientoRepository.findByUsuarioIdAndClaveStartingWith(2L, "podio:")).thenReturn(List.of(podio));

        Map<String, Object> perfil = servicio.perfil(1L, 2L).orElseThrow();

        assertEquals(GamificacionService.apodoDe(otro), perfil.get("apodo"));
        assertEquals(3, perfil.get("nivel"));
        assertEquals(8, perfil.get("mejorRacha"));
        assertEquals(1, ((Map<?, ?>) perfil.get("medallas")).get("oro"));
        Map<?, ?> aria = (Map<?, ?>) perfil.get("aria");
        assertEquals("perro", aria.get("variante"));
        assertEquals("orgullosa", aria.get("animo"));
        assertEquals(List.of("bufanda", "gorro"), aria.get("coleccion"));
        String todo = perfil.toString();
        assertFalse(todo.contains("Nombre Real"));
        assertFalse(perfil.containsKey("mejorNota") || perfil.containsKey("ultimaNota") || perfil.containsKey("nota"));
    }

    @Test
    void noSeVeElPerfilDeAlguienDeOtroCurso() {
        Usuario yo = alumno(1), otro = alumno(2);
        matricular(yo, 10);
        matricular(otro, 11);

        assertTrue(servicio.perfil(1L, 2L).isEmpty());
        verify(progresoAlumnoService, never()).progresoDe(any());
    }

    @Test
    void unPerfilOcultoSoloMuestraElApodoAUnCompanero() {
        Usuario yo = alumno(1), otro = alumno(2);
        otro.setPerfilVisible(false);
        matricular(yo, 10);
        matricular(otro, 10);

        Map<String, Object> perfil = servicio.perfil(1L, 2L).orElseThrow();

        assertEquals(true, perfil.get("privado"));
        assertFalse(perfil.containsKey("racha"));
        verify(progresoAlumnoService, never()).progresoDe(any());
    }

    @Test
    void elDuenoVeSuPerfilAunqueLoHayaOcultado() {
        Usuario yo = alumno(1);
        yo.setPerfilVisible(false);
        conProgreso(1, 0);
        when(intentoRepository.findByUsuarioIdOrderByFechaDesc(1L)).thenReturn(List.of());

        Map<String, Object> perfil = servicio.perfil(1L, 1L).orElseThrow();

        assertEquals(false, perfil.get("privado"));
        assertEquals(false, perfil.get("visible"));
        assertEquals("durmiendo", ((Map<?, ?>) perfil.get("aria")).get("animo"));
    }

    @Test
    void soloSeGuardanEstilosDeAriaQueExisten() {
        assertThrows(IllegalArgumentException.class, () -> servicio.ajustar(1L, null, "dragon"));
        verify(userRepository, never()).save(any());
    }
}
