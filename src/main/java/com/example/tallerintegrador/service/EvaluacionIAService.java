package com.example.tallerintegrador.service;

import com.example.tallerintegrador.entidades.mongodb.ArchivoPrompt;
import com.example.tallerintegrador.entidades.mongodb.Prompt;
import com.example.tallerintegrador.entidades.postgres.*;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EvaluacionIAService {

    private final GeminiService geminiService;

    private Prompt prompt;

    private ArchivoPrompt archivoPrompt;

    private Usuario usuario;

    private Pregunta pregunta;

    private Respuesta respuesta;

    public void generarPreguntaDesdeArchivo(String contenidoArchivo, String tipoPregunta){

        String promptIA =
                "Genera una pregunta tipo "
                        + tipoPregunta
                        + " basada en el siguiente archivo:\n\n"
                        + contenidoArchivo;

        String respuestaIA =
                geminiService.askGemini(promptIA);

        pregunta = new Pregunta();

        pregunta.setPregunta(respuestaIA);

        if(tipoPregunta.equalsIgnoreCase("multiple")){

            pregunta.setTipodepregunta(Tipo.Opcion_Multiple);

        } else if(tipoPregunta.equalsIgnoreCase("completar")){

            pregunta.setTipodepregunta(Tipo.Responder);
        }
    }

    public void generarOpcionesMultiple(){

        String promptOpciones =
                "Genera 4 opciones para la siguiente pregunta "
                        + "e indica cuál es la correcta:\n\n"
                        + pregunta.getPregunta();

        String opcionesIA =
                geminiService.askGemini(promptOpciones);

        // Simulación temporal
        for(int i = 1; i <= 4; i++){

            Respuesta respuesta = new Respuesta();

            respuesta.setRespuesta("Opción " + i);

            // Relacionar con la pregunta
            respuesta.setPregunta(pregunta);

            // Solo una correcta
            if(i == 1){

                respuesta.setValor(true);

            } else {

                respuesta.setValor(false);
            }
        }
    }

    // Generar respuesta mínima aceptable
    public void generarRespuestaCompletar(){

        String promptRespuesta =
                "Genera una respuesta correcta y corta "
                        + "para la siguiente pregunta:\n\n"
                        + pregunta.getPregunta();

        String respuestaIA =
                geminiService.askGemini(promptRespuesta);

        Respuesta respuesta = new Respuesta();

        respuesta.setRespuesta(respuestaIA);

        respuesta.setPregunta(pregunta);

        respuesta.setValor(true);
    }

    public RespuestaUsuario responderPregunta(
            Usuario usuario,
            Pregunta pregunta,
            Respuesta respuestaSeleccionada
    ){

        RespuestaUsuario respuestaUsuario =
                new RespuestaUsuario();

        respuestaUsuario.setUsuario(usuario);

        respuestaUsuario.setPregunta(pregunta);

        respuestaUsuario.setRespuestaSeleccionada(
                respuestaSeleccionada
        );

        // Guardar fecha de respuesta
        respuestaUsuario.setFechaCreacion(
                LocalDateTime.now()
        );

        // Verificación rápida
        respuestaUsuario.setCorrecta(
                respuestaSeleccionada.isValor()
        );

        return respuestaUsuario;
    }

    public void evaluarPreguntasPendientes(
            List<RespuestaUsuario> respuestasUsuario
    ){

        LocalDateTime ahora = LocalDateTime.now();

        for(RespuestaUsuario respuestaUsuario : respuestasUsuario){

            // Fecha de respuesta del usuario
            LocalDateTime fechaRespuesta =
                    respuestaUsuario.getFechaCreacion();

            // Límite de evaluación = 5 minutos
            LocalDateTime limite =
                    fechaRespuesta.plusMinutes(5);

            // Evaluar si ya pasó el tiempo
            if(ahora.isAfter(limite)){

                Pregunta pregunta =
                        respuestaUsuario.getPregunta();

                Respuesta respuestaSeleccionada =
                        respuestaUsuario
                                .getRespuestaSeleccionada();

                String promptEvaluacion =
                        "Evalúa la siguiente respuesta.\n\n"

                                + "Pregunta:\n"
                                + pregunta.getPregunta()

                                + "\n\nRespuesta del usuario:\n"
                                + respuestaSeleccionada
                                .getRespuesta()

                                + "\n\nIndica si es correcta "
                                + "o incorrecta y explica.";

                String resultadoIA =
                        geminiService.askGemini(
                                promptEvaluacion
                        );

                System.out.println(resultadoIA);
            }
        }
    }



}