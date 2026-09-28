package com.example.tallerintegrador.service.spike;

import com.example.tallerintegrador.agents.preguntas.ComiteDePreguntasService;
import com.example.tallerintegrador.agents.preguntas.CorrectorEstiloAgent;
import com.example.tallerintegrador.agents.preguntas.ReactivoReescrito;
import com.example.tallerintegrador.service.metricas.MetricasEstandarizadasService;
import dev.langchain4j.service.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El control de calidad decide qué reactivo llega al alumno: aprueba, repara o descarta. Estas
 * pruebas fijan las tres decisiones y, sobre todo, la que más riesgo tiene: que NO se repare un
 * reactivo rechazado por contenido, porque pulir la redacción de una pregunta cuya respuesta
 * está mal es maquillarla.
 */
class ControlDeCalidadReactivosTest {

    private ComiteDePreguntasService comite;
    private CorrectorEstiloAgent corrector;
    private ControlDeCalidadReactivos control;

    @BeforeEach
    void setUp() {
        comite = mock(ComiteDePreguntasService.class);
        corrector = mock(CorrectorEstiloAgent.class);
        control = new ControlDeCalidadReactivos(comite, corrector, new MetricasEstandarizadasService());
        ReflectionTestUtils.setField(control, "comiteHabilitado", true);
        ReflectionTestUtils.setField(control, "maxLote", 10);
        ReflectionTestUtils.setField(control, "correctorHabilitado", true);
        ReflectionTestUtils.setField(control, "umbralLegibilidad", 0.0); // desactiva el disparo por legibilidad
        ReflectionTestUtils.setField(control, "margenGeneracion", 3);
    }

    private Map<String, Object> reactivo(String enunciado) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enunciado", enunciado);
        m.put("opciones_o_respuesta", List.of("A) El sustantivo", "B) El verbo"));
        m.put("respuesta_correcta", "A) El sustantivo");
        return m;
    }

    private ComiteDePreguntasService.Dictamen aprobado(int puntuacion) {
        return new ComiteDePreguntasService.Dictamen(true, List.of(), "",
                Map.of("contenido", puntuacion, "pedagogico", puntuacion, "forma", puntuacion));
    }

    private ComiteDePreguntasService.Dictamen rechazadoPorForma() {
        return new ComiteDePreguntasService.Dictamen(false, List.of("Alternativas de largo desigual"),
                "Empareja la longitud de las alternativas",
                Map.of("contenido", 5, "pedagogico", 4, "forma", 2));
    }

    private ComiteDePreguntasService.Dictamen rechazadoPorContenido() {
        return new ComiteDePreguntasService.Dictamen(false, List.of("La respuesta marcada no es correcta"),
                "Regenera la pregunta", Map.of("contenido", 1, "pedagogico", 4, "forma", 5));
    }

    private void corrigeCon(String enunciado, List<String> opciones) {
        when(corrector.corregir(anyString())).thenReturn(
                Result.<ReactivoReescrito>builder()
                        .content(new ReactivoReescrito(enunciado, opciones, "se emparejaron las alternativas"))
                        .build());
    }

    @Test
    @DisplayName("Con el comité apagado no se llama a nadie y pasan todas")
    void comiteApagado() {
        ReflectionTestUtils.setField(control, "comiteHabilitado", false);
        List<Object> preguntas = new ArrayList<>(List.of(reactivo("¿Cuál es el núcleo del sujeto?")));

        var resumen = control.aplicar(preguntas, "OPCION_MULTIPLE", "material", "Comprender");

        assertThat(preguntas).hasSize(1);
        assertThat(resumen.aprobados()).isEqualTo(1);
        verify(comite, never()).revisarLote(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Un reactivo rechazado solo por forma se repara y se conserva")
    void reparaLoQueSoloFallaEnForma() {
        List<Object> preguntas = new ArrayList<>(List.of(reactivo("¿Cuál de las alternativas es el núcleo del sujeto?")));
        when(comite.revisarLote(any(), any(), any(), any())).thenReturn(List.of(rechazadoPorForma()));
        corrigeCon("¿Cuál es el núcleo del sujeto en la oración?", List.of("A) El sustantivo", "B) El verbo"));

        var resumen = control.aplicar(preguntas, "OPCION_MULTIPLE", "material", "Comprender");

        assertThat(preguntas).hasSize(1);
        assertThat(resumen.corregidos()).isEqualTo(1);
        assertThat(resumen.descartados()).isZero();
        Map<?, ?> corregido = (Map<?, ?>) preguntas.get(0);
        assertThat(corregido.get("enunciado")).isEqualTo("¿Cuál es el núcleo del sujeto en la oración?");
        assertThat(corregido.get("correccion_estilo")).isEqualTo("se emparejaron las alternativas");
    }

    @Test
    @DisplayName("Un reactivo rechazado por contenido se descarta sin pasar por el corrector")
    void noMaquillaLoQueFallaEnContenido() {
        List<Object> preguntas = new ArrayList<>(List.of(reactivo("¿Cuál es el núcleo del sujeto?")));
        when(comite.revisarLote(any(), any(), any(), any())).thenReturn(List.of(rechazadoPorContenido()));

        var resumen = control.aplicar(preguntas, "OPCION_MULTIPLE", "material", "Comprender");

        assertThat(preguntas).isEmpty();
        assertThat(resumen.descartados()).isEqualTo(1);
        verify(corrector, never()).corregir(anyString());
    }

    @Test
    @DisplayName("Si la corrección altera el significado, se descarta el reactivo igual")
    void correccionInvalidaNoSalvaElReactivo() {
        List<Object> preguntas = new ArrayList<>(List.of(reactivo("El sujeto no siempre va al inicio de la oración.")));
        when(comite.revisarLote(any(), any(), any(), any())).thenReturn(List.of(rechazadoPorForma()));
        // El corrector quita la negación: cambia el valor de verdad del reactivo.
        corrigeCon("El sujeto siempre va al inicio de la oración.", List.of("A) El sustantivo", "B) El verbo"));

        var resumen = control.aplicar(preguntas, "OPCION_MULTIPLE", "material", "Comprender");

        assertThat(preguntas).isEmpty();
        assertThat(resumen.corregidos()).isZero();
        assertThat(resumen.descartados()).isEqualTo(1);
    }

    @Test
    @DisplayName("Las aprobadas se ordenan de mejor a peor puntuación del comité")
    void ordenaPorPuntuacion() {
        List<Object> preguntas = new ArrayList<>(List.of(
                reactivo("Pregunta floja sobre el sujeto de la oración simple."),
                reactivo("Pregunta buena sobre el sujeto de la oración simple.")));
        when(comite.revisarLote(any(), any(), any(), any())).thenReturn(List.of(aprobado(3), aprobado(5)));

        control.aplicar(preguntas, "OPCION_MULTIPLE", "material", "Comprender");

        Map<?, ?> primera = (Map<?, ?>) preguntas.get(0);
        assertThat(String.valueOf(primera.get("enunciado"))).contains("buena");
    }

    @Test
    @DisplayName("Si el comité falla entero, las preguntas siguen su camino sin revisar")
    void comiteCaidoNoDejaAlAlumnoSinEvaluacion() {
        List<Object> preguntas = new ArrayList<>(List.of(reactivo("¿Cuál es el núcleo del sujeto?")));
        when(comite.revisarLote(any(), any(), any(), any())).thenThrow(new RuntimeException("sin conexión"));

        var resumen = control.aplicar(preguntas, "OPCION_MULTIPLE", "material", "Comprender");

        assertThat(preguntas).hasSize(1);
        assertThat(resumen.descartados()).isZero();
    }

    @Test
    @DisplayName("Con el comite encendido se piden reactivos de mas para absorber los descartes")
    void pideDeMasCuandoElComiteFiltra() {
        assertThat(control.cantidadAGenerar(5)).isEqualTo(8);
    }

    @Test
    @DisplayName("El margen nunca supera el tamano maximo de lote que el comite puede revisar")
    void elMargenRespetaElMaximoDeLote() {
        assertThat(control.cantidadAGenerar(9)).isEqualTo(10);
    }

    @Test
    @DisplayName("Con el comite apagado no se pide nada de mas: nadie va a descartar")
    void sinComiteNoHayMargen() {
        ReflectionTestUtils.setField(control, "comiteHabilitado", false);
        assertThat(control.cantidadAGenerar(5)).isEqualTo(5);
    }

    @Test
    @DisplayName("Sin comite pero con corrector, todas se reescriben y NINGUNA se descarta")
    void correctorSoloNoDescartaNada() {
        ReflectionTestUtils.setField(control, "comiteHabilitado", false);
        List<Object> preguntas = new ArrayList<>(List.of(
                reactivo("Cual es el nucleo del sujeto en la oracion simple?"),
                reactivo("Que funcion cumple el sujeto dentro de la oracion?")));
        corrigeCon("¿Cual es el nucleo del sujeto en la oracion simple?",
                List.of("A) El sustantivo", "B) El verbo"));

        var resumen = control.aplicar(preguntas, "OPCION_MULTIPLE", "material", "Comprender");

        assertThat(preguntas).hasSize(2);
        assertThat(resumen.descartados()).isZero();
        assertThat(resumen.corregidos()).isPositive();
        verify(comite, never()).revisarLote(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Sin comite, una reescritura invalida deja el reactivo original en pie")
    void correctorSoloConservaSiLaReescrituraNoPasa() {
        ReflectionTestUtils.setField(control, "comiteHabilitado", false);
        List<Object> preguntas = new ArrayList<>(List.of(
                reactivo("El sujeto no siempre va al inicio de la oracion.")));
        // El corrector quita la negacion: la guarda debe rechazarlo.
        corrigeCon("El sujeto siempre va al inicio de la oracion.", List.of("A) El sustantivo", "B) El verbo"));

        var resumen = control.aplicar(preguntas, "OPCION_MULTIPLE", "material", "Comprender");

        assertThat(preguntas).hasSize(1);
        assertThat(resumen.corregidos()).isZero();
        assertThat(resumen.descartados()).isZero();
        Map<?, ?> intacto = (Map<?, ?>) preguntas.get(0);
        assertThat(String.valueOf(intacto.get("enunciado"))).contains("no siempre");
    }

    private Map<String, Object> conConcepto(String enunciado, String concepto) {
        Map<String, Object> m = reactivo(enunciado);
        m.put("concepto", concepto);
        return m;
    }

    @Test
    @DisplayName("Cinco reactivos sobre cinco subtemas distintos dan cobertura total")
    void coberturaCompleta() {
        List<Object> p = new ArrayList<>(List.of(
                conConcepto("a", "Sujeto"), conConcepto("b", "Predicado"),
                conConcepto("c", "Nucleo nominal"), conConcepto("d", "Modificadores")));
        assertThat(control.coberturaDeConceptos(p)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("El mismo subtema escrito distinto NO cuenta como cobertura nueva")
    void repeticionDisfrazadaSeDetecta() {
        // Acentos y mayusculas no crean un concepto nuevo: es la misma pregunta con otra ropa.
        List<Object> p = new ArrayList<>(List.of(
                conConcepto("a", "Signo lingüístico"), conConcepto("b", "signo linguistico"),
                conConcepto("c", "SIGNO LINGUISTICO"), conConcepto("d", "Predicado")));
        assertThat(control.coberturaDeConceptos(p)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("Una lista vacía no rompe nada")
    void listaVacia() {
        List<Object> preguntas = new ArrayList<>();
        var resumen = control.aplicar(preguntas, "OPCION_MULTIPLE", "material", "Comprender");
        assertThat(resumen.recibidos()).isZero();
    }
}
