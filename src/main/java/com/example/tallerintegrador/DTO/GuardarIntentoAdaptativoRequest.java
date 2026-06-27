package com.example.tallerintegrador.DTO;

import com.example.tallerintegrador.entidades.postgres.TipoEvaluacion;
import java.util.List;

public record GuardarIntentoAdaptativoRequest(
        Long usuarioId,
        String semanaId,
        Double notaFinal,
        Integer tiempoEmpleadoSegundos,
        Integer numeroIntentos,
        TipoEvaluacion tipoEvaluacion,
        List<GuardarIntentoRequest.RespuestaDetalle> respuestas
) {}
