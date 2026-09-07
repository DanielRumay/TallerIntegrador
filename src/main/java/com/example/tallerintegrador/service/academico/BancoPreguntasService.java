package com.example.tallerintegrador.service.academico;

import com.example.tallerintegrador.entidades.postgres.Pregunta;
import com.example.tallerintegrador.entidades.postgres.RespuestaUsuario;
import com.example.tallerintegrador.entidades.postgres.Semana;
import com.example.tallerintegrador.repository.PreguntaRepository;
import com.example.tallerintegrador.repository.RespuestaUsuarioRepository;
import com.example.tallerintegrador.repository.SemanaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Banco de preguntas del docente: todo lo que la IA generó para una semana, con lo que cada
 * alumno respondió y por qué se calificó así.
 *
 * POR QUÉ EXISTE. Hasta ahora las preguntas se guardaban pero solo las veía el alumno que las
 * respondió, en su propio historial. El docente que sube el material no tenía forma de leer
 * qué produjo el modelo a partir de ese material. Eso deja dos agujeros:
 *
 *   1) Nadie audita al generador. Si el modelo produce un reactivo mal formulado o fuera de
 *      temario, el defecto solo se descubre por la queja de un alumno.
 *   2) La tesis afirma que las preguntas se ajustan al material y a Bloom, pero la evidencia
 *      no era inspeccionable por un humano: solo existía agregada en las métricas.
 *
 * Esta vista es el sustrato de ambas cosas. No calcula nada nuevo: lee lo que ya está
 * persistido (enunciado, nivel Bloom, respuesta correcta, respuesta del alumno y la
 * retroalimentación literal del juez) y lo ordena para que se pueda leer.
 *
 * QUÉ NO ES. No es una métrica de aprendizaje. La tasa de acierto por pregunta mide
 * dificultad observada en la muestra de quienes la respondieron, que puede ser un solo
 * alumno; con n pequeño no significa nada, y por eso se devuelve `vecesRespondida` al lado
 * para que quien la lea vea sobre cuántos casos se calculó.
 */
@Service
@RequiredArgsConstructor
public class BancoPreguntasService {

    private final PreguntaRepository preguntaRepository;
    private final RespuestaUsuarioRepository respuestaUsuarioRepository;
    private final SemanaRepository semanaRepository;

    /**
     * Debajo de este número de respuestas, la tasa de acierto no se presenta como dificultad.
     * Con dos alumnos, "50% de acierto" es una moneda al aire, y mostrarlo como índice de
     * dificultad induce a error a quien lo lee.
     */
    private static final int MINIMO_PARA_DIFICULTAD = 5;

    /** Una pregunta con su rendimiento observado y las respuestas que recibió. */
    public record PreguntaDelBanco(
            Long id,
            String enunciado,
            String tipo,
            String nivelBloom,
            String nivelDificultad,
            String conceptos,
            String respuestaCorrecta,
            List<String> opciones,
            int vecesRespondida,
            int aciertos,
            Double tasaAcierto,
            String lecturaDificultad,
            List<RespuestaDeAlumno> respuestas) {}

    public record RespuestaDeAlumno(
            String alumno,
            String respuesta,
            boolean correcta,
            String retroalimentacion,
            String fecha) {}

    /**
     * Comprueba que la semana pertenece a un curso del docente.
     *
     * Va aquí y no en el controlador porque es la regla de negocio, no de transporte: sin
     * esto, cualquier docente autenticado podría leer las respuestas de los alumnos de otro
     * cambiando el id en la URL. Es el mismo fallo (IDOR) que ya se corrigió en las rutas
     * de intentos.
     */
    @Transactional(readOnly = true)
    public boolean puedeVer(Long semanaId, Long usuarioId, boolean esAdmin) {
        if (esAdmin) return true;
        if (usuarioId == null) return false;
        return semanaRepository.findById(semanaId)
                .map(Semana::getCurso)
                .map(c -> c.getProfesor() != null && usuarioId.equals(c.getProfesor().getId()))
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> porSemana(Long semanaId) {
        List<Pregunta> preguntas = preguntaRepository.findBySemanaId(semanaId);

        Map<String, Object> salida = new LinkedHashMap<>();
        semanaRepository.findById(semanaId).ifPresent(s -> {
            salida.put("semanaId", s.getId());
            salida.put("numSem", s.getNumSem());
            salida.put("nombreTema", s.getNombreTema());
        });

        if (preguntas.isEmpty()) {
            salida.put("total", 0);
            salida.put("porNivelBloom", Map.of());
            salida.put("sinRespuestaCorrecta", 0L);
            salida.put("preguntas", List.of());
            salida.put("nota", "Todavía no se ha generado ninguna pregunta para esta semana.");
            return salida;
        }

        List<Long> ids = preguntas.stream().map(Pregunta::getId).toList();

        // En bloque y no pregunta por pregunta: 60 reactivos serían 60 consultas separadas.
        Map<Long, List<RespuestaUsuario>> porPregunta = respuestaUsuarioRepository
                .findByPreguntaIdIn(ids).stream()
                .filter(r -> r.getPregunta() != null)
                .collect(Collectors.groupingBy(r -> r.getPregunta().getId()));

        List<PreguntaDelBanco> filas = new ArrayList<>();
        for (Pregunta p : preguntas) {
            List<RespuestaUsuario> respuestas = porPregunta.getOrDefault(p.getId(), List.of());
            int total = respuestas.size();
            int aciertos = (int) respuestas.stream().filter(RespuestaUsuario::isCorrecta).count();

            Double tasa = total == 0 ? null : (double) aciertos / total;
            String lectura;
            if (total == 0) {
                lectura = "Sin responder todavía.";
            } else if (total < MINIMO_PARA_DIFICULTAD) {
                lectura = "Solo " + total + " respuesta(s): no alcanza para estimar dificultad.";
            } else {
                lectura = describirDificultad(tasa);
            }

            filas.add(new PreguntaDelBanco(
                    p.getId(),
                    p.getPregunta(),
                    p.getTipodepregunta() == null ? null : p.getTipodepregunta().name(),
                    p.getNivelBloom(),
                    p.getNivelDificultad() == null ? null : p.getNivelDificultad().name(),
                    p.getConceptos(),
                    p.getRespuestaCorrecta(),
                    opcionesDe(p),
                    total,
                    aciertos,
                    tasa,
                    lectura,
                    respuestas.stream()
                            .sorted(Comparator.comparing(
                                    RespuestaUsuario::getFechaCreacion,
                                    Comparator.nullsLast(Comparator.reverseOrder())))
                            .map(r -> new RespuestaDeAlumno(
                                    r.getUsuario() == null ? "—" : r.getUsuario().getNombre(),
                                    textoDe(r),
                                    r.isCorrecta(),
                                    r.getRetroalimentacion(),
                                    r.getFechaCreacion() == null ? null
                                            : r.getFechaCreacion().toString()))
                            .toList()));
        }

        Map<String, Long> porNivel = preguntas.stream()
                .map(p -> p.getNivelBloom() == null ? "sin etiqueta" : p.getNivelBloom())
                .collect(Collectors.groupingBy(n -> n, LinkedHashMap::new, Collectors.counting()));

        salida.put("total", filas.size());
        salida.put("porNivelBloom", porNivel);
        // Se cuenta y se expone: una pregunta sin respuesta correcta guardada no puede
        // devolverle al alumno qué era lo correcto, y eso el docente debe poder verlo.
        salida.put("sinRespuestaCorrecta", filas.stream()
                .filter(f -> f.respuestaCorrecta() == null || f.respuestaCorrecta().isBlank())
                .count());
        salida.put("preguntas", filas);
        return salida;
    }

    private List<String> opcionesDe(Pregunta p) {
        if (p.getRespuestas() == null) return List.of();
        return p.getRespuestas().stream()
                .map(r -> r.getRespuesta())
                .filter(t -> t != null && !t.isBlank())
                .toList();
    }

    /**
     * El texto de lo que respondió el alumno, venga de una pregunta abierta o de una opción
     * marcada. Se unifica aquí porque al docente le da igual el mecanismo: quiere leerlo.
     */
    private String textoDe(RespuestaUsuario r) {
        if (r.getRespuestaTexto() != null && !r.getRespuestaTexto().isBlank()) {
            return r.getRespuestaTexto();
        }
        return r.getRespuestaSeleccionada() == null
                ? "—"
                : r.getRespuestaSeleccionada().getRespuesta();
    }

    /**
     * Traduce la tasa de acierto a lenguaje llano. Los cortes son los de uso corriente en
     * análisis de ítems: por debajo de .30 el reactivo se considera difícil y por encima de
     * .80 fácil (Ebel y Frisbie, Essentials of Educational Measurement, 5.ª ed., 1991).
     */
    private String describirDificultad(double tasa) {
        if (tasa < 0.30) return "Difícil: la mayoría falla. Revisa si el enunciado es claro.";
        if (tasa < 0.55) return "Exigente, pero dentro de lo razonable.";
        if (tasa < 0.80) return "Dificultad media.";
        return "Fácil: casi todos aciertan. Distingue poco entre niveles.";
    }
}
