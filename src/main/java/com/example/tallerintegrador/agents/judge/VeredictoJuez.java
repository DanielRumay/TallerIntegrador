package com.example.tallerintegrador.agents.judge;

import dev.langchain4j.model.output.structured.Description;

import java.util.List;

/**
 * Forma de retorno estructurada del juez. Reemplaza el JSON de texto libre que antes se
 * parseaba con Jackson y, si fallaba, con expresiones regulares (ver el histórico de
 * AgentJudgeAgent). LangChain4j genera el esquema JSON que se envía a Gemini directamente
 * a partir de este record — el esquema y el código nunca pueden desincronizarse porque son
 * la misma fuente.
 *
 * detalles y textoCorregido solo se completan para preguntas de tipo DETECCION_ERRORES; en
 * el resto de tipos quedan nulos y AgentJudgeAgent los omite del mapa de salida.
 */
public record VeredictoJuez(

        @Description("true si la respuesta del estudiante es correcta según la rúbrica; false en caso contrario")
        boolean esCorrecta,

        @Description("Puntaje de 0 a 100 proporcional al acierto de la respuesta")
        int puntaje,

        @Description("Explicación pedagógica de 3 a 6 oraciones: por qué está bien o mal, y qué se esperaba")
        String explicacion,

        @Description("Solo para preguntas DETECCION_ERRORES: una entrada por cada error del enunciado, " +
                "indicando si la corrección del estudiante fue válida. Vacío para cualquier otro tipo.")
        List<DetalleCorreccion> detalles,

        @Description("Solo para preguntas DETECCION_ERRORES: el texto completo del enunciado con todas las " +
                "correcciones correctas aplicadas. Nulo para cualquier otro tipo.")
        String textoCorregido
) {
    public record DetalleCorreccion(
            @Description("La palabra o frase corta que contenía el error en el enunciado original")
            String palabraConError,

            @Description("true si la corrección que dio el estudiante para esta palabra es válida o " +
                    "semánticamente equivalente a la esperada")
            boolean esCorrecto
    ) {}
}
