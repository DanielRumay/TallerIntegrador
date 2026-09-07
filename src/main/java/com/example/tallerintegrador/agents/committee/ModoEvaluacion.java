package com.example.tallerintegrador.agents.committee;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Los formatos de evaluación que el comité puede recomendar. Conjunto CERRADO.
 *
 * POR QUÉ EXISTE. Antes el comité solo escribía prosa y el frontend la escaneaba buscando
 * palabras:
 *
 *     case "video": return texto.includes("video") || texto.includes("explicativo")
 *                        || texto.includes("lección") || texto.includes("narrac");
 *
 * Eso convierte una decisión pedagógica en una lotería de vocabulario. Si el comité escribe
 * "conviene que practique redactando argumentos", casa con ABIERTA por "redacc"; si escribe
 * "que escriba un ensayo", no casa con NADA y el alumno se queda sin ninguna recomendación
 * activa, sin que nadie se entere. El propio `@Description` del campo pedía al modelo
 * "mencionar explícitamente el formato para que el frontend lo desbloquee": se le estaba
 * pidiendo que acertara las palabras clave.
 *
 * Con códigos, la recomendación es un dato y no una interpretación. La prosa se conserva
 * aparte, porque al alumno hay que explicarle POR QUÉ, no solo iluminarle un botón.
 */
public enum ModoEvaluacion {

    /** Conversación socrática con Aria. */
    AVATAR,
    /** Videolección explicativa. */
    VIDEO,
    OPCION_MULTIPLE,
    VERDADERO_FALSO,
    /** Respuesta abierta, calificada por el juez. */
    ABIERTA,
    /** Encontrar errores conceptuales en un texto. */
    DETECCION_ERRORES,
    /** Pregunta apoyada en una ilustración. */
    VISUAL_QUIZ;

    /** Nombres válidos, para incrustarlos en el prompt del comité. */
    public static String listaParaPrompt() {
        return String.join(", ", Arrays.stream(values()).map(Enum::name).toList());
    }

    /**
     * Filtra lo que devolvió el modelo, quedándose solo con los códigos que existen.
     *
     * Un modelo puede inventarse "MODO_SOCRATICO" o devolver "video" en minúsculas. Sin este
     * filtro, un valor inventado llegaría a la interfaz y no encendería nada — otro fallo
     * silencioso. Aquí se descarta y queda constancia en el resultado.
     */
    public static Resultado depurar(List<String> crudos) {
        if (crudos == null || crudos.isEmpty()) {
            return new Resultado(List.of(), List.of());
        }

        // LinkedHashSet: sin repetir y conservando el orden en que los propuso el comité, que
        // suele ir de más a menos prioritario.
        Set<ModoEvaluacion> validos = new LinkedHashSet<>();
        List<String> descartados = new java.util.ArrayList<>();

        for (String crudo : crudos) {
            if (crudo == null || crudo.isBlank()) continue;
            try {
                validos.add(ModoEvaluacion.valueOf(crudo.strip().toUpperCase()));
            } catch (IllegalArgumentException noExiste) {
                descartados.add(crudo.strip());
            }
        }
        return new Resultado(List.copyOf(validos), List.copyOf(descartados));
    }

    public record Resultado(List<ModoEvaluacion> validos, List<String> descartados) {}
}
