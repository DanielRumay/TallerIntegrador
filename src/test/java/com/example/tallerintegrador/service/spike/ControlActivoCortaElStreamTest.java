package com.example.tallerintegrador.service.spike;

import com.example.tallerintegrador.agents.preguntas.ComiteDePreguntasService;
import com.example.tallerintegrador.agents.preguntas.CorrectorEstiloAgent;
import com.example.tallerintegrador.service.metricas.MetricasEstandarizadasService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * El emisor del streaming decide si manda los trozos consultando estaActivo(). Es una regla
 * facil de romper sin notarlo, y romperla corrompe datos del alumno:
 *
 * el cliente pinta las preguntas conforme llegan y guarda las respuestas por numero de
 * diapositiva; si el control reordena o descarta el lote despues, la respuesta ya dada queda
 * pegada a otra pregunta. Por eso, con el control activo, no se emite nada hasta tener el lote
 * definitivo.
 */
class ControlActivoCortaElStreamTest {

    private ControlDeCalidadReactivos conComite(boolean habilitado) {
        var control = new ControlDeCalidadReactivos(
                mock(ComiteDePreguntasService.class),
                mock(CorrectorEstiloAgent.class),
                new MetricasEstandarizadasService());
        ReflectionTestUtils.setField(control, "comiteHabilitado", habilitado);
        return control;
    }

    @Test
    @DisplayName("Con el comite encendido el control se declara activo: no se emite incremental")
    void activoConComiteEncendido() {
        assertThat(conComite(true).estaActivo()).isTrue();
    }

    @Test
    @DisplayName("Con el comite apagado se conserva el streaming incremental de siempre")
    void inactivoConComiteApagado() {
        assertThat(conComite(false).estaActivo()).isFalse();
    }
}
