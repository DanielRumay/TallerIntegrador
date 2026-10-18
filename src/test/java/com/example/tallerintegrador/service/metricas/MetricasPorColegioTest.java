package com.example.tallerintegrador.service.metricas;

import com.example.tallerintegrador.entidades.postgres.EventoMetricaIA;
import com.example.tallerintegrador.entidades.postgres.Permiso;
import com.example.tallerintegrador.entidades.postgres.Rol;
import com.example.tallerintegrador.entidades.postgres.TipoEventoIA;
import com.example.tallerintegrador.repository.DebateAgentesRepository;
import com.example.tallerintegrador.repository.EventoMetricaIARepository;
import com.example.tallerintegrador.repository.UserRepository;
import com.example.tallerintegrador.seguridad.SesionDePrueba;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Los eventos del juez y de la recuperación RAG no tienen usuario. Si las métricas se acotaran
 * por usuario, desaparecerían del panel del administrador; por eso cada evento guarda su colegio.
 */
class MetricasPorColegioTest {

    @AfterEach
    void limpiar() {
        SesionDePrueba.cerrar();
    }

    private static EventoMetricaIA juez(Long colegioId, Long usuarioId) {
        EventoMetricaIA e = EventoMetricaIA.de(TipoEventoIA.JUEZ_EVALUACION, "PARSEO_OK");
        e.setColegioId(colegioId);
        e.setUsuarioId(usuarioId);
        e.setLatenciaMs(1000L);
        return e;
    }

    @Test
    @DisplayName("La telemetría registrada en una petición guarda el colegio de quien la hizo")
    void registraElColegio() {
        EventoMetricaIARepository repo = mock(EventoMetricaIARepository.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        TelemetriaIAService telemetria = new TelemetriaIAService(repo, tx);
        SesionDePrueba.iniciar(5L, "alumno@a.pe", Rol.STUDENT, 1L, false, Set.of());

        EventoMetricaIA evento = EventoMetricaIA.de(TipoEventoIA.JUEZ_EVALUACION, "PARSEO_OK");
        telemetria.registrar(evento);

        assertThat(evento.getColegioId()).isEqualTo(1L);
        assertThat(evento.getUsuarioId()).as("no se inventa un usuario").isNull();
    }

    @Test
    @DisplayName("El panel cuenta los eventos sin usuario de su colegio y no los de otro")
    void filtraPorColegioDelEvento() {
        EventoMetricaIARepository repo = mock(EventoMetricaIARepository.class);
        UserRepository users = mock(UserRepository.class);
        MetricasIAService servicio = new MetricasIAService(repo, mock(DebateAgentesRepository.class));
        ReflectionTestUtils.setField(servicio, "userRepository", users);
        when(users.idsDelColegio(1L)).thenReturn(Set.of(5L));
        when(repo.findByTipoAndFechaBetween(eq(TipoEventoIA.JUEZ_EVALUACION), any(), any())).thenReturn(List.of(
                juez(1L, null),     // del colegio 1, sin usuario: cuenta
                juez(1L, null),     // del colegio 1, sin usuario: cuenta
                juez(2L, null),     // de otro colegio: no cuenta
                juez(null, 5L),     // antiguo sin colegio, de un usuario del colegio 1: cuenta
                juez(null, 99L)));  // antiguo sin colegio, de otro usuario: no cuenta
        SesionDePrueba.iniciar(9L, "adm@a.pe", Rol.ADMIN, 1L, true, EnumSet.of(Permiso.METRICAS_IA_VER));

        Map<String, Object> juez = servicio.juez(LocalDateTime.now().minusDays(1), LocalDateTime.now());

        assertThat(((Number) juez.get("evaluaciones")).intValue()).isEqualTo(3);
    }
}
