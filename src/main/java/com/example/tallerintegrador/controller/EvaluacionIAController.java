package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.entidades.postgres.Pregunta;
import com.example.tallerintegrador.entidades.postgres.Respuesta;
import com.example.tallerintegrador.entidades.postgres.RespuestaUsuario;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.service.EvaluacionIAService;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/evaluacion")
@RequiredArgsConstructor
public class EvaluacionIAController {

    private final EvaluacionIAService evaluacionIAService;

    // Generar pregunta desde archivo
    @PostMapping("/pregunta")
    public void generarPreguntaDesdeArchivo(

            @RequestParam String contenidoArchivo,
            @RequestParam String tipoPregunta

    ){

        evaluacionIAService.generarPreguntaDesdeArchivo(
                contenidoArchivo,
                tipoPregunta
        );
    }

    // Generar opciones o respuesta
    @PostMapping("/respuestas")
    public void generarOpcionesORespuesta(

            @RequestParam String tipoPregunta

    ){

        if(tipoPregunta.equalsIgnoreCase("multiple")){

            evaluacionIAService.generarOpcionesMultiple();

        } else if(tipoPregunta.equalsIgnoreCase("completar")){

            evaluacionIAService.generarRespuestaCompletar();
        }
    }

    // Guardar respuesta del usuario
    @PostMapping("/responder")
    public RespuestaUsuario guardarRespuestaUsuario(

            @RequestBody Usuario usuario,

            @RequestBody Pregunta pregunta,

            @RequestBody Respuesta respuestaSeleccionada

    ){

        return evaluacionIAService.responderPregunta(
                usuario,
                pregunta,
                respuestaSeleccionada
        );
    }

    // Evaluar respuestas del usuario
    @PostMapping("/evaluar")
    public void evaluarRespuestasUsuario(

            @RequestBody
            List<RespuestaUsuario> respuestasUsuario

    ){

        evaluacionIAService.evaluarPreguntasPendientes(
                respuestasUsuario
        );
    }
}