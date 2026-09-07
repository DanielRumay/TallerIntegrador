package com.example.tallerintegrador.agents;

import com.example.tallerintegrador.entidades.postgres.Intento;
import com.example.tallerintegrador.entidades.postgres.NivelConocimiento;
import com.example.tallerintegrador.entidades.postgres.TipoEvaluacion;
import com.example.tallerintegrador.repository.IntentoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;

/**
 * Pruebas de la guardia determinista del comité.
 *
 * A diferencia del debate, esta lógica no depende del LLM: se puede probar de forma
 * exhaustiva y reproducible. Es precisamente el motivo de su existencia — que la decisión
 * final sobre el nivel de un alumno sea auditable y no dependa de una salida probabilística.
 */
@ExtendWith(MockitoExtension.class)
class VerificadorAgentTest {

    @Mock
    private IntentoRepository intentoRepository;

    private VerificadorAgent verificador;

    @BeforeEach
    void setUp() {
        verificador = new VerificadorAgent(intentoRepository);
    }

    private void conNotas(Double... notas) {
        List<Intento> intentos = new ArrayList<>();
        LocalDateTime base = LocalDateTime.now();
        for (int i = 0; i < notas.length; i++) {
            Intento in = new Intento();
            in.setNota(notas[i]);
            in.setFecha(base.minusDays(i));       // la primera es la más reciente
            in.setTipoEvaluacion(TipoEvaluacion.FORMATIVA);
            intentos.add(in);
        }
        lenient().when(intentoRepository.findByUsuarioIdOrderByFechaDesc(anyLong())).thenReturn(intentos);
    }

    @Test
    @DisplayName("Aprueba AVANZADO cuando el rendimiento alto está sostenido")
    void apruebaAvanzadoConRendimientoSostenido() {
        conNotas(17.5, 16.0, 12.0);

        var v = verificador.verificar(1L, NivelConocimiento.INTERMEDIO, NivelConocimiento.AVANZADO);

        assertFalse(v.vetado());
        assertEquals(NivelConocimiento.AVANZADO, v.nivelAplicado());
    }

    @Test
    @DisplayName("Veta AVANZADO cuando solo el último intento fue alto")
    void vetaAvanzadoPorRendimientoNoSostenido() {
        conNotas(18.0, 9.0);

        var v = verificador.verificar(1L, NivelConocimiento.INTERMEDIO, NivelConocimiento.AVANZADO);

        assertTrue(v.vetado());
        assertEquals(NivelConocimiento.INTERMEDIO, v.nivelAplicado());
        assertTrue(v.motivo().contains("16"), "El motivo debe explicitar el umbral incumplido");
    }

    @Test
    @DisplayName("Veta AVANZADO cuando no hay historial suficiente")
    void vetaAvanzadoSinHistorialSuficiente() {
        conNotas(19.0);

        var v = verificador.verificar(1L, NivelConocimiento.INTERMEDIO, NivelConocimiento.AVANZADO);

        assertTrue(v.vetado());
        assertEquals(NivelConocimiento.INTERMEDIO, v.nivelAplicado());
    }

    @Test
    @DisplayName("Veta el salto de dos niveles en una sola evaluación")
    void vetaSaltoDeDosNiveles() {
        conNotas(18.0, 17.0);

        var v = verificador.verificar(1L, NivelConocimiento.PRINCIPIANTE, NivelConocimiento.AVANZADO);

        assertTrue(v.vetado());
        assertEquals(NivelConocimiento.INTERMEDIO, v.nivelAplicado());
        assertTrue(v.motivo().contains("dos niveles"));
    }

    @Test
    @DisplayName("Veta INTERMEDIO cuando el último intento fue desaprobatorio")
    void vetaIntermedioConUltimoIntentoBajo() {
        conNotas(8.0, 14.0);

        var v = verificador.verificar(1L, NivelConocimiento.PRINCIPIANTE, NivelConocimiento.INTERMEDIO);

        assertTrue(v.vetado());
        assertEquals(NivelConocimiento.PRINCIPIANTE, v.nivelAplicado());
    }

    @Test
    @DisplayName("Aprueba INTERMEDIO cuando el último intento fue aprobatorio")
    void apruebaIntermedioConUltimoIntentoSuficiente() {
        conNotas(13.0, 8.0);

        var v = verificador.verificar(1L, NivelConocimiento.PRINCIPIANTE, NivelConocimiento.INTERMEDIO);

        assertFalse(v.vetado());
        assertEquals(NivelConocimiento.INTERMEDIO, v.nivelAplicado());
    }

    @Test
    @DisplayName("Descender a PRINCIPIANTE nunca se veta")
    void permiteDescenso() {
        conNotas(6.0, 7.0);

        var v = verificador.verificar(1L, NivelConocimiento.AVANZADO, NivelConocimiento.PRINCIPIANTE);

        assertFalse(v.vetado());
        assertEquals(NivelConocimiento.PRINCIPIANTE, v.nivelAplicado());
    }

    @Test
    @DisplayName("Una propuesta nula conserva el nivel vigente en vez de degradar al alumno")
    void propuestaNulaConservaNivel() {
        var v = verificador.verificar(1L, NivelConocimiento.INTERMEDIO, null);

        assertTrue(v.vetado());
        assertEquals(NivelConocimiento.INTERMEDIO, v.nivelAplicado());
    }

    @Test
    @DisplayName("Las evaluaciones diagnósticas no cuentan para los umbrales de promoción")
    void ignoraDiagnosticas() {
        Intento diagnostica = new Intento();
        diagnostica.setNota(20.0);
        diagnostica.setFecha(LocalDateTime.now());
        diagnostica.setTipoEvaluacion(TipoEvaluacion.DIAGNOSTICA);

        Intento formativa = new Intento();
        formativa.setNota(9.0);
        formativa.setFecha(LocalDateTime.now().minusDays(1));
        formativa.setTipoEvaluacion(TipoEvaluacion.FORMATIVA);

        lenient().when(intentoRepository.findByUsuarioIdOrderByFechaDesc(anyLong()))
                .thenReturn(List.of(diagnostica, formativa));

        var v = verificador.verificar(1L, NivelConocimiento.PRINCIPIANTE, NivelConocimiento.INTERMEDIO);

        assertTrue(v.vetado(), "El 20 de la prueba ACRA no debe habilitar la promoción");
        assertEquals(NivelConocimiento.PRINCIPIANTE, v.nivelAplicado());
    }
}
