package com.example.tallerintegrador.DTO;

import java.util.List;

public record GuardarIntentoRequest(
        Long usuarioId,
        String semanaId,
        Double notaFinal,
        List<RespuestaDetalle> respuestas
) {
    public record RespuestaDetalle(
            String preguntaTexto,
            String tipoPregunta,
            String respuestaEstudiante,
            boolean esCorrecta
    ) {}
}