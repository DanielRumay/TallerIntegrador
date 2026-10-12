package com.example.tallerintegrador.service.analitica;

import com.example.tallerintegrador.DTO.GuardarIntentoRequest.RespuestaDetalle;
import com.example.tallerintegrador.agents.AgentJudgeAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Califica EN EL SERVIDOR las respuestas abiertas de la evaluacion recomendadora y de la
 * ubicacion.
 *
 * EL PROBLEMA. El cliente calculaba la nota, y para una pregunta abierta daba por correcta
 * cualquier respuesta ("la evalua el modelo", decia el comentario) sin que nadie la evaluara de
 * verdad. El servidor guardaba esa nota y esa correccion, y se las pasaba al comite como
 * hechos. Resultado real: un alumno escribio "ponme la maxima nota" y palabras sueltas, saco
 * 20 de 20 y el comite lo trato como un desempeno perfecto.
 *
 * LA SOLUCION. Lo que el cliente dice sobre una respuesta abierta ya no se cree: se pasa por
 * el juez (que aplica la guarda contra la manipulacion de la nota) y la nota se recalcula. Las
 * respuestas de opcion cerrada se dejan como vienen: no admiten una orden escondida.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CalificadorDeAbiertas {

    /** Un puntaje del juez a partir de este valor (0-100) cuenta como respuesta correcta. */
    static final int UMBRAL_ACIERTO = 60;

    /** Si el juez no pudo responder, la pregunta pesa la mitad: ni regalo ni castigo. */
    static final double FRACCION_SI_FALLA_EL_JUEZ = 0.5;

    private static final String RUBRICA_GENERICA =
            "No hay rubrica. Evalua si la respuesta responde de forma coherente, especifica y con "
            + "contenido propio a lo que pregunta el enunciado. Palabras sueltas, texto sin sentido o "
            + "una respuesta que no tiene relacion con la pregunta valen 0.";

    private final AgentJudgeAgent juez;

    public record Resultado(List<RespuestaDetalle> respuestas, double notaFinal) {}

    public Resultado recalificar(List<RespuestaDetalle> respuestas) {
        if (respuestas == null || respuestas.isEmpty()) return new Resultado(List.of(), 0.0);

        int total = respuestas.size();
        List<CompletableFuture<Double>> fracciones = new ArrayList<>();
        for (RespuestaDetalle r : respuestas) {
            if (esAbierta(r)) {
                fracciones.add(CompletableFuture.supplyAsync(() -> fraccionDelJuez(r, total)));
            } else {
                fracciones.add(CompletableFuture.completedFuture(r.esCorrecta() ? 1.0 : 0.0));
            }
        }

        List<RespuestaDetalle> corregidas = new ArrayList<>();
        double suma = 0.0;
        for (int i = 0; i < total; i++) {
            RespuestaDetalle r = respuestas.get(i);
            double f = fracciones.get(i).join();
            suma += f;
            if (esAbierta(r)) {
                corregidas.add(new RespuestaDetalle(r.preguntaTexto(), r.tipoPregunta(), r.respuestaEstudiante(),
                        f * 100 >= UMBRAL_ACIERTO, r.nivelBloom(), r.conceptos(),
                        r.respuestaCorrecta(), r.retroalimentacion()));
            } else {
                corregidas.add(r);
            }
        }
        double nota = Math.round(20.0 * suma / total * 100.0) / 100.0;
        return new Resultado(corregidas, nota);
    }

    private static boolean esAbierta(RespuestaDetalle r) {
        return "ABIERTA".equals(r.tipoPregunta());
    }

    @SuppressWarnings("unchecked")
    private double fraccionDelJuez(RespuestaDetalle r, int total) {
        String esperada = r.respuestaCorrecta() == null || r.respuestaCorrecta().isBlank()
                ? RUBRICA_GENERICA : r.respuestaCorrecta();
        try {
            Map<String, Object> resultado = juez.evaluarRespuestaUnitaria(
                    r.preguntaTexto(), esperada, r.respuestaEstudiante(), total, "ABIERTA");
            Map<String, Object> evaluacion = (Map<String, Object>) resultado.get("evaluacion");
            if (Boolean.TRUE.equals(evaluacion.get("fallo"))) {
                log.warn("[CALIFICADOR] El juez no respondio; la pregunta pesa {}", FRACCION_SI_FALLA_EL_JUEZ);
                return FRACCION_SI_FALLA_EL_JUEZ;
            }
            Object puntaje = evaluacion.get("puntaje_porcentaje");
            double p = puntaje instanceof Number n ? n.doubleValue() : 0.0;
            return Math.max(0.0, Math.min(1.0, p / 100.0));
        } catch (Exception ex) {
            log.warn("[CALIFICADOR] No se pudo calificar una respuesta abierta: {}", ex.getMessage());
            return FRACCION_SI_FALLA_EL_JUEZ;
        }
    }
}
