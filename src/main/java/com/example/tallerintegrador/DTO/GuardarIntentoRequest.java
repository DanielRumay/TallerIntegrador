package com.example.tallerintegrador.DTO;

import java.util.List;

public record GuardarIntentoRequest(
        Long usuarioId,
        Long semanaId,
        Double notaFinal,
        Integer tiempoEmpleadoSegundos,
        Integer numeroIntentos,
        String tipoEvaluacion,
        List<RespuestaDetalle> respuestas
) {
    public record RespuestaDetalle(
            String preguntaTexto,
            String tipoPregunta,
            String respuestaEstudiante,
            boolean esCorrecta
    ) {}
}