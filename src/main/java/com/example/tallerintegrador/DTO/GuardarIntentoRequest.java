package com.example.tallerintegrador.DTO;

import java.util.List;

public record GuardarIntentoRequest(
        Long usuarioId,
        String semanaId,
        Double notaFinal,
        String tecnica,
        List<RespuestaDetalle> respuestas
) {
    public record RespuestaDetalle(
            String preguntaTexto,
            String tipoPregunta,
            String respuestaEstudiante,
            boolean esCorrecta,
            // Opcionales: el frontend aún no los reenvía al guardar el intento (los recibió
            // al generar la evaluación, en preguntas_json.evaluacion_bloom, pero no los
            // hacía ida y vuelta). Quedan nullable a propósito: mientras el frontend no se
            // actualice para reenviarlos, el mapa de conocimiento por concepto/Bloom
            // simplemente no tendrá datos para esa respuesta, en vez de romper el guardado.
            String nivelBloom,
            String conceptos,
            // Mismo criterio de nullable que los dos anteriores: alimentan el historial
            // descargable, y si el frontend no los envía el intento se guarda igual.
            String respuestaCorrecta,
            String retroalimentacion
    ) {}
}

