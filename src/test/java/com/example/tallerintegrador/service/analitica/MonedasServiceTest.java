package com.example.tallerintegrador.service.analitica;

import com.example.tallerintegrador.entidades.postgres.Intento;
import com.example.tallerintegrador.entidades.postgres.MovimientoMonedas;
import com.example.tallerintegrador.entidades.postgres.TurnoTutorSocratico;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.repository.IntentoRepository;
import com.example.tallerintegrador.repository.MovimientoMonedasRepository;
import com.example.tallerintegrador.repository.TurnoTutorSocraticoRepository;
import com.example.tallerintegrador.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MonedasServiceTest {

    @Mock private IntentoRepository intentoRepository;
    @Mock private TurnoTutorSocraticoRepository turnoRepository;
    @Mock private MovimientoMonedasRepository movimientoRepository;
    @Mock private UserRepository userRepository;

    private MonedasService servicio;
    private final LocalDateTime hoy = LocalDateTime.of(2026, 11, 2, 9, 0);

    @BeforeEach
    void setUp() {
        servicio = new MonedasService(intentoRepository, turnoRepository, movimientoRepository, userRepository);
    }

    private Intento intento(LocalDateTime fecha, double nota, Integer segundos) {
        Intento i = new Intento();
        i.setFecha(fecha);
        i.setNota(nota);
        i.setTiempoEmpleadoSegundos(segundos);
        return i;
    }

    private TurnoTutorSocratico turno(int escalon) {
        TurnoTutorSocratico t = new TurnoTutorSocratico();
        t.setEscalonConsumido(escalon);
        t.setFecha(hoy);
        return t;
    }

    @Test
    void cadaEvaluacionPagaCincoYUnaNotaAltaTresMas() {
        assertEquals(8 + 5, MonedasService.monedasPorPractica(List.of(
                intento(hoy, 16, 300), intento(hoy.plusMinutes(10), 9, 300))));
    }

    @Test
    void elTopeDiarioPagaCincoCompletasCuatroAMitadYLuegoNada() {
        List<Intento> doce = new ArrayList<>();
        for (int k = 0; k < 12; k++) doce.add(intento(hoy.plusMinutes(k * 5), 10, 120));

        // 5 completas (5) + 4 a mitad (2, división entera) + 3 sin monedas
        assertEquals(5 * 5 + 4 * 2, MonedasService.monedasPorPractica(doce));
    }

    @Test
    void elTopeSeReiniciaCadaDia() {
        List<Intento> intentos = new ArrayList<>();
        for (int k = 0; k < 9; k++) intentos.add(intento(hoy.plusMinutes(k), 10, 120));
        intentos.add(intento(hoy.plusDays(1), 10, 120));

        assertEquals(5 * 5 + 4 * 2 + 5, MonedasService.monedasPorPractica(intentos));
    }

    @Test
    void sinEsfuerzoNoHayMonedasYNoGastaElCupoDelDia() {
        List<Intento> intentos = new ArrayList<>();
        for (int k = 0; k < 10; k++) intentos.add(intento(hoy.plusMinutes(k), 10, 5)); // 5 s: a la carrera
        intentos.add(intento(hoy.plusHours(1), 10, 200));
        intentos.add(intento(hoy.plusHours(2), 10, null)); // intento antiguo sin tiempo: cuenta

        assertEquals(10, MonedasService.monedasPorPractica(intentos));
    }

    @Test
    void ariaPagaMasSinPistasYTieneTopeDiario() {
        assertEquals(3 + 1, MonedasService.monedasPorAria(List.of(turno(1), turno(2))));
        List<TurnoTutorSocratico> muchos = new ArrayList<>();
        for (int k = 0; k < 15; k++) muchos.add(turno(1));
        assertEquals(10 * 3, MonedasService.monedasPorAria(muchos));
    }

    @Test
    void losHitosUsanLaRachaMasLargaDeLaHistoriaYNoBajan() {
        List<Intento> intentos = new ArrayList<>();
        for (int d = 0; d < 8; d++) intentos.add(intento(hoy.minusDays(40 + d), 12, 100)); // racha de 8, ya cortada
        intentos.add(intento(hoy, 12, 100));

        assertEquals(8, MonedasService.rachaMasLarga(intentos));
        assertEquals(20, MonedasService.monedasPorHitos(8));
        assertEquals(20 + 50, MonedasService.monedasPorHitos(30));
        assertEquals(20 + 50 + 150, MonedasService.monedasPorHitos(120));
    }

    @Test
    void unPremioConLaMismaClaveSeCobraUnaSolaVez() {
        when(movimientoRepository.existsByUsuarioIdAndClave(1L, "podio:1:2026-10-26")).thenReturn(false, true);

        assertTrue(servicio.premiar(1L, 50, "1.º puesto", "podio:1:2026-10-26"));
        assertFalse(servicio.premiar(1L, 50, "1.º puesto", "podio:1:2026-10-26"));
        verify(movimientoRepository, times(1)).saveAndFlush(any());
    }

    @Test
    void siDosPeticionesChocanElPremioNoSeDuplica() {
        when(movimientoRepository.existsByUsuarioIdAndClave(any(), any())).thenReturn(false);
        when(movimientoRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk"));

        assertFalse(servicio.premiar(1L, 50, "1.º puesto", "podio:1:2026-10-26"));
    }

    @Test
    void noSePuedeGastarMasDeLoQueSeTiene() {
        when(userRepository.bloquearPorId(1L)).thenReturn(Optional.of(new Usuario()));
        when(intentoRepository.findByUsuarioIdOrderByFechaDesc(1L)).thenReturn(List.of(intento(hoy, 10, 100)));
        when(turnoRepository.findByUsuarioIdAndCerradoTrueOrderByFechaAsc(1L)).thenReturn(List.of());
        when(movimientoRepository.sumaPorTipo(1L, MovimientoMonedas.Tipo.PREMIO)).thenReturn(0);
        when(movimientoRepository.sumaPorTipo(1L, MovimientoMonedas.Tipo.GASTO)).thenReturn(0);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> servicio.gastar(1L, 10, "Pista"));
        assertTrue(e.getMessage().contains("tienes 5"));
        verify(movimientoRepository, never()).save(any());

        assertEquals(1, servicio.gastar(1L, 4, "Pista"));
        verify(movimientoRepository).save(argThat(m -> m.getCantidad() == -4 && m.getTipo() == MovimientoMonedas.Tipo.GASTO));
    }
}
