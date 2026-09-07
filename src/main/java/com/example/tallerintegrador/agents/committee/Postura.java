package com.example.tallerintegrador.agents.committee;

import dev.langchain4j.model.output.structured.Description;

import java.util.List;

/**
 * Turno estructurado de un agente del comité.
 *
 * Reemplaza al String de 1-2 oraciones que cada agente devolvía antes. La diferencia no es
 * cosmética: con prosa libre, dos agentes podían contradecirse sin que nada lo detectara —
 * el Coordinador solo veía texto y "sintetizaba" sobre lo que fuera. Con evidencia() como
 * campo obligatorio y separado del mensaje, cada afirmación queda trazada a un dato
 * concreto (una nota, una fecha, una pregunta fallada), y confianza() permite que el
 * VerificadorAgent (ver agents/VerificadorAgent.java) pese cuánto puede confiar en la
 * propuesta antes de aplicarla.
 */
public record Postura(

        @Description("Nombre del agente que emite esta postura, ej. '[Agente Evaluador]'")
        String agente,

        @Description("Nivel propuesto para el alumno: PRINCIPIANTE, INTERMEDIO o AVANZADO. " +
                "Puede ser null en turnos que solo analizan sin proponer nivel todavía.")
        String nivelPropuesto,

        @Description("Qué tan seguro está el agente de su propuesta, de 0.0 (sin evidencia " +
                "suficiente) a 1.0 (evidencia clara y consistente)")
        double confianza,

        @Description("Citas concretas de datos reales obtenidos con las herramientas " +
                "(notas, fechas, preguntas falladas) que respaldan esta postura. NUNCA una " +
                "afirmación sin un dato que la sostenga.")
        List<String> evidencia,

        @Description("1 a 2 oraciones en tono de debate, dirigidas al resto del comité, " +
                "para mostrar en la transcripción legible")
        String mensaje
) {}
