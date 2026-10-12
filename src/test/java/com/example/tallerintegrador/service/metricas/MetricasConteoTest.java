package com.example.tallerintegrador.service.metricas;

import com.example.tallerintegrador.entidades.postgres.EventoMetricaIA;
import com.example.tallerintegrador.entidades.postgres.TipoEventoIA;
import com.example.tallerintegrador.repository.DebateAgentesRepository;
import com.example.tallerintegrador.repository.EventoMetricaIARepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cada pregunta generada deja DOS eventos GENERACION_PREGUNTA (conformidad de Bloom y calidad
 * textual), y la deduplicacion etiqueta con DUPLICADO_CONFIRMADO / SIMILAR_PERO_DISTINTA.
 * Estas pruebas fijan que el panel cuente cada cosa una sola vez y no ignore esas etiquetas.
 */
class MetricasConteoTest {

    private final EventoMetricaIARepository repo = mock(EventoMetricaIARepository.class);
    private final MetricasIAService servicio =
            new MetricasIAService(repo, mock(DebateAgentesRepository.class));
    private final LocalDateTime desde = LocalDateTime.now().minusDays(1);
    private final LocalDateTime hasta = LocalDateTime.now();

    private EventoMetricaIA evento(TipoEventoIA tipo, String etiqueta, String bloom, Double valor) {
        EventoMetricaIA e = EventoMetricaIA.de(tipo, etiqueta);
        e.setNivelBloom(bloom);
        e.setValor(valor);
        return e;
    }

    @Test
    @DisplayName("La conformidad de Bloom no cuenta los eventos de calidad textual")
    void conformidadNoDuplicaElDenominador() {
        List<EventoMetricaIA> eventos = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            eventos.add(evento(TipoEventoIA.GENERACION_PREGUNTA, "CONFORME", i < 4 ? "Analizar" : "Comprender", null));
            eventos.add(evento(TipoEventoIA.GENERACION_PREGUNTA, "CALIDAD_TEXTUAL", null, 45.0));
        }
        when(repo.findByTipoAndFechaBetween(eq(TipoEventoIA.GENERACION_PREGUNTA), any(), any())).thenReturn(eventos);

        Map<String, Object> r = servicio.conformidadBloom(desde, hasta);

        assertThat(r.get("preguntas_generadas")).isEqualTo(10L);
        assertThat((Double) r.get("tasa_conformidad_declarada")).isEqualTo(100.0);
        assertThat((Double) r.get("tasa_hots")).isEqualTo(40.0);
        assertThat(r.get("cumple_meta_oe1")).isEqualTo(true);
    }

    @Test
    @DisplayName("La deduplicacion cuenta los duplicados confirmados por el modelo")
    void deduplicacionCuentaLaZonaDudosa() {
        List<EventoMetricaIA> eventos = new ArrayList<>();
        for (int i = 0; i < 90; i++) eventos.add(evento(TipoEventoIA.DEDUP_CANDIDATA, "ACEPTADA", null, 0.0));
        for (int i = 0; i < 6; i++) eventos.add(evento(TipoEventoIA.DEDUP_CANDIDATA, "SIMILAR_PERO_DISTINTA", null, 0.88));
        for (int i = 0; i < 4; i++) eventos.add(evento(TipoEventoIA.DEDUP_CANDIDATA, "DUPLICADO_CONFIRMADO", null, 0.92));
        when(repo.findByTipoAndFechaBetween(eq(TipoEventoIA.DEDUP_CANDIDATA), any(), any())).thenReturn(eventos);

        Map<String, Object> r = servicio.deduplicacion(desde, hasta);

        assertThat(r.get("candidatas_evaluadas")).isEqualTo(100L);
        assertThat(r.get("duplicados_confirmados_por_modelo")).isEqualTo(4L);
        assertThat(r.get("similares_pero_distintas_aceptadas")).isEqualTo(6L);
        assertThat((Double) r.get("tasa_duplicados")).isEqualTo(4.0);
        assertThat((Double) r.get("score_medio_rechazo")).isEqualTo(0.92);
    }
}
