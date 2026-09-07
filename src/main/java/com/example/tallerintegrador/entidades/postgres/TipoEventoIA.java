package com.example.tallerintegrador.entidades.postgres;

/**
 * Categorías de evento instrumentado del pipeline de IA.
 * Cada valor corresponde a un punto de medición declarado en el informe técnico.
 */
public enum TipoEventoIA {
    /** Una pregunta candidata fue emitida por el generador (con su nivel Bloom declarado). */
    GENERACION_PREGUNTA,
    /** Una pregunta candidata pasó por el filtro de deduplicación (aceptada o rechazada). */
    DEDUP_CANDIDATA,
    /** Una recuperación RAG se resolvió con un umbral efectivo determinado. */
    RAG_RECUPERACION,
    /** El agente juez evaluó una respuesta del estudiante. */
    JUEZ_EVALUACION,
    /** El comité de agentes cerró un debate con una decisión (vetada o no). */
    COMITE_DEBATE,
    /** Una imagen generada para un reactivo pasó por el validador de alineación semántica. */
    VALIDACION_IMAGEN,
    /** Un reactivo generado fue rechazado por filtración de referencias estructurales al documento fuente. */
    GUARDIA_ENUNCIADO
}
