package com.example.tallerintegrador.agents.preguntas;

import dev.langchain4j.service.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * El comité decide qué preguntas ve el alumno. Si aprueba de más, la evaluación pierde
 * validez; si rechaza de más, el alumno se queda sin examen. Ambos fallos son caros, así que
 * se prueban los dos lados y, sobre todo, el comportamiento ante fallos parciales.
 */
class ComiteDePreguntasServiceTest {

    /** Crítico de mentira que devuelve siempre el mismo veredicto, sin llamar a ningún modelo. */
    private CriticoDePreguntas critico(boolean aprueba, int puntuacion, String problema) {
        return instrucciones -> Result.<VeredictoCritico>builder()
                .content(new VeredictoCritico(aprueba, puntuacion, problema, "corrige esto", "evidencia"))
                .build();
    }

    /** Crítico que revienta, para comprobar que un fallo no bloquea al alumno. */
    private CriticoDePreguntas criticoRoto() {
        return instrucciones -> {
            throw new RuntimeException("sin conexión con el modelo");
        };
    }

    private ComiteDePreguntasService comite(CriticoDePreguntas c1, CriticoDePreguntas c2, CriticoDePreguntas c3) {
        return new ComiteDePreguntasService(c1, c2, c3);
    }

    private static final String ENUNCIADO_LIMPIO =
            "¿Por qué las plantas necesitan luz solar para producir su alimento?";

    @Test
    @DisplayName("Con los tres críticos conformes, la pregunta se aprueba")
    void apruebaCuandoTodosAprueban() {
        var c = comite(critico(true, 5, ""), critico(true, 4, ""), critico(true, 4, ""));

        var d = c.revisar(ENUNCIADO_LIMPIO, "Porque la usan en la fotosíntesis",
                List.of("a", "b", "c"), "material", "COMPRENDER", null);

        assertTrue(d.aprobada());
        assertTrue(d.problemas().isEmpty());
    }

    @Test
    @DisplayName("Basta que UN crítico rechace para que la pregunta no salga")
    void bastaUnRechazo() {
        var c = comite(critico(true, 5, ""),
                       critico(false, 2, "Pide analizar pero se responde localizando un dato"),
                       critico(true, 4, ""));

        var d = c.revisar(ENUNCIADO_LIMPIO, "respuesta", List.of("a", "b"), "material", "ANALIZAR", null);

        assertFalse(d.aprobada());
        assertEquals(1, d.problemas().size());
        assertTrue(d.instruccionesDeCorreccion().contains("corrige"));
    }

    @Test
    @DisplayName("Una puntuación baja rechaza aunque el crítico diga que aprueba")
    void puntuacionBajaRechazaAunqueApruebe() {
        // Un crítico puede marcar aprobada=true con un 2 por complacencia. La nota lo delata:
        // sin esta comprobación, el "sí" formal bastaría para colar una pregunta mediocre.
        var c = comite(critico(true, 2, "Los distractores son descartables a simple vista"),
                       critico(true, 5, ""), critico(true, 5, ""));

        var d = c.revisar(ENUNCIADO_LIMPIO, "respuesta", List.of("a", "b"), "material", "COMPRENDER", null);

        assertFalse(d.aprobada(), "una puntuación de 2 no debe pasar aunque diga aprobada");
    }

    @Test
    @DisplayName("La guarda determinista rechaza sin gastar una sola llamada al modelo")
    void guardaDeterministaNoLlamaAlModelo() {
        // Si algún crítico se ejecutara, este comité lanzaría excepción y el test fallaría.
        var c = comite(criticoRoto(), criticoRoto(), criticoRoto());

        var d = c.revisar("Según el punto 3, ¿qué se concluye?", "respuesta",
                List.of("a", "b"), "material", "COMPRENDER", null);

        assertFalse(d.aprobada());
        assertTrue(d.puntuaciones().containsKey("estructura"),
                "debe rechazarse por la guarda, no por los críticos");
    }

    @Test
    @DisplayName("Un crítico caído no bloquea la pregunta")
    void criticoCaidoNoBloquea() {
        // Preferimos una pregunta con una dimensión sin revisar a un alumno sin evaluación
        // por un fallo de red.
        var c = comite(critico(true, 5, ""), criticoRoto(), critico(true, 4, ""));

        var d = c.revisar(ENUNCIADO_LIMPIO, "respuesta", List.of("a", "b"), "material", "COMPRENDER", null);

        assertTrue(d.aprobada(), "un fallo técnico no debe contar como rechazo");
        assertEquals(-1, d.puntuaciones().get("pedagogico"),
                "la dimensión no revisada debe quedar marcada, no fingir un aprobado");
    }

    @Test
    @DisplayName("El razonamiento se emite paso a paso y NUNCA incluye la respuesta")
    void elRazonamientoNoFiltraLaRespuesta() {
        List<String> emitidos = new ArrayList<>();
        BiConsumer<String, Object> emisor = (evento, dato) -> {
            if (dato instanceof Map<?, ?> m && m.get("paso") != null) {
                emitidos.add(m.get("paso").toString());
            }
        };

        var c = comite(critico(true, 5, ""), critico(true, 4, ""), critico(true, 4, ""));
        String respuestaSecreta = "La clorofila absorbe la luz";

        c.revisar(ENUNCIADO_LIMPIO, respuestaSecreta,
                List.of("La clorofila absorbe la luz", "Distractor uno"), "material", "COMPRENDER", emisor);

        assertFalse(emitidos.isEmpty(), "debe emitirse el razonamiento paso a paso");

        // Lo que se transmite en vivo lo ve el alumno MIENTRAS resuelve. Si el razonamiento
        // arrastrara la clave, el comité que existe para dar validez la estaría destruyendo.
        String todo = String.join(" ", emitidos).toLowerCase();
        assertFalse(todo.contains("clorofila"),
                "el razonamiento emitido no puede contener la respuesta: " + todo);
        assertFalse(todo.contains("distractor"), todo);
    }

    @Test
    @DisplayName("Funciona con preguntas abiertas, sin alternativas")
    void soportaPreguntasAbiertas() {
        var c = comite(critico(true, 5, ""), critico(true, 4, ""), critico(true, 4, ""));

        var d = c.revisar(ENUNCIADO_LIMPIO, "criterio esperado", null, "material", "EVALUAR", null);

        assertTrue(d.aprobada());
    }

    @Test
    @DisplayName("Se acumulan los problemas de todos los críticos, no solo el primero")
    void acumulaTodosLosProblemas() {
        // El generador necesita TODAS las correcciones a la vez: si solo recibiera la primera,
        // cada reintento arreglaría un defecto y descubriría el siguiente, gastando una ronda
        // completa por cada uno.
        var c = comite(critico(false, 1, "No se responde con el material"),
                       critico(false, 2, "El nivel no corresponde"),
                       critico(false, 2, "Distractores absurdos"));

        var d = c.revisar(ENUNCIADO_LIMPIO, "respuesta", List.of("a", "b"), "material", "ANALIZAR", null);

        assertEquals(3, d.problemas().size());
    }
}
