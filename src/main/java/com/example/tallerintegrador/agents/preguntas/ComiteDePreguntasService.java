package com.example.tallerintegrador.agents.preguntas;

import com.example.tallerintegrador.service.util.EnunciadoGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Comite que revisa cada pregunta ANTES de que la vea el alumno.
 *
 * BASE: EduAgentQG (arXiv:2511.11635), flujo multi-agente de generacion de preguntas con
 * ciclo generar - evaluar - regenerar y evaluacion consciente del objetivo de aprendizaje.
 * La separacion en criticos por dimension sigue el principio de MAJ-EVAL, donde varios
 * jueces con roles distintos evaluan aspectos separados en vez de un unico juez generalista.
 *
 * TRES DIMENSIONES, TRES CRITICOS:
 *
 *   CONTENIDO   - se puede responder con el material? la respuesta marcada es la correcta?
 *   PEDAGOGICO  - corresponde al nivel de Bloom pedido? es apropiado para 2do de secundaria?
 *   FORMA       - los distractores son plausibles? el enunciado se sostiene solo?
 *
 * Un solo revisor al que se le piden cinco criterios a la vez tiende a fijarse en el primero
 * y aprobar el resto por inercia. Separarlos cuesta dos llamadas mas y evita ese sesgo.
 *
 * GUARDA DETERMINISTA POR ENCIMA DE LOS CRITICOS. Antes de gastar una sola llamada al modelo
 * se pasa EnunciadoGuard, que detecta referencias estructurales ("segun el punto 3") con
 * expresiones regulares. Es el mismo principio que VerificadorAgent en el comite de nivel:
 * lo que se puede comprobar con una regla no se le pregunta a un modelo, porque la regla no
 * alucina y ademas es gratis.
 */
@Slf4j
@Service
public class ComiteDePreguntasService {

    private final CriticoDePreguntas criticoContenido;
    private final CriticoDePreguntas criticoPedagogico;
    private final CriticoDePreguntas criticoForma;

    public ComiteDePreguntasService(
            @Qualifier("criticoContenido") CriticoDePreguntas criticoContenido,
            @Qualifier("criticoPedagogico") CriticoDePreguntas criticoPedagogico,
            @Qualifier("criticoForma") CriticoDePreguntas criticoForma) {
        this.criticoContenido = criticoContenido;
        this.criticoPedagogico = criticoPedagogico;
        this.criticoForma = criticoForma;
    }

    /** Puntuacion por debajo de la cual se rechaza aunque el critico haya dicho "aprobada". */
    private static final int PUNTUACION_MINIMA = 3;

    public record Dictamen(
            boolean aprobada,
            List<String> problemas,
            String instruccionesDeCorreccion,
            Map<String, Integer> puntuaciones
    ) {}

    /**
     * @param emisor recibe los eventos de la deliberacion para transmitirlos al alumno en
     *               vivo. IMPORTANTE: nunca se le pasa la respuesta correcta ni los
     *               distractores — solo el razonamiento sobre la calidad. Que el alumno vea
     *               "revisando que la pregunta se pueda responder con el material" es
     *               transparencia; que vea la clave de respuesta es filtrarle el examen.
     */
    public Dictamen revisar(
            String enunciado,
            String respuestaCorrecta,
            List<String> opciones,
            String contexto,
            String nivelBloom,
            BiConsumer<String, Object> emisor) {

        List<String> problemas = new ArrayList<>();
        List<String> correcciones = new ArrayList<>();
        Map<String, Integer> puntuaciones = new LinkedHashMap<>();

        // Guarda determinista primero: gratis, instantanea y no alucina.
        emitir(emisor, "Comprobando que la pregunta se entienda por si sola");
        if (EnunciadoGuard.contieneReferenciaEstructural(enunciado)) {
            log.info("[COMITE-PREGUNTAS] Rechazo determinista por referencia estructural");
            emitir(emisor, "La pregunta hacia referencia a una parte del documento que el alumno no ve");
            return new Dictamen(false,
                    List.of("El enunciado remite a una parte del documento que el alumno no puede ver."),
                    "Reescribe el enunciado para que sea autosuficiente: nada de "
                    + "'segun el punto N', 'en el texto anterior' ni referencias a secciones.",
                    Map.of("estructura", 1));
        }

        String opcionesTexto = (opciones == null || opciones.isEmpty())
                ? "(pregunta abierta, sin alternativas)"
                : String.join(" | ", opciones);

        record Revision(String clave, String etiquetaAlumno, CriticoDePreguntas critico, String datos) {}

        List<Revision> revisiones = List.of(
                new Revision("contenido", "Verificando que se pueda responder con el material",
                        criticoContenido,
                        "MATERIAL DE ESTUDIO:\n" + recortar(contexto, 6000)
                                + "\n\nPREGUNTA:\n" + enunciado
                                + "\n\nRESPUESTA MARCADA COMO CORRECTA:\n" + respuestaCorrecta),

                new Revision("pedagogico", "Comprobando que corresponda al nivel pedido",
                        criticoPedagogico,
                        "NIVEL DE BLOOM SOLICITADO: " + nivelBloom
                                + "\n\nPREGUNTA:\n" + enunciado
                                + "\n\nALTERNATIVAS:\n" + opcionesTexto),

                new Revision("forma", "Revisando la redaccion y las alternativas",
                        criticoForma,
                        "PREGUNTA:\n" + enunciado
                                + "\n\nALTERNATIVAS:\n" + opcionesTexto
                                + "\n\nRESPUESTA CORRECTA:\n" + respuestaCorrecta)
        );

        for (Revision r : revisiones) {
            emitir(emisor, r.etiquetaAlumno());
            try {
                VeredictoCritico v = r.critico().revisar(r.datos()).content();
                puntuaciones.put(r.clave(), v.puntuacion());

                // Se exige AMBAS cosas: que apruebe y que la nota supere el minimo. Un critico
                // puede marcar `aprobada = true` con un 2 por complacencia; la nota lo delata.
                if (!v.aprobada() || v.puntuacion() < PUNTUACION_MINIMA) {
                    if (v.problema() != null && !v.problema().isBlank()) problemas.add(v.problema());
                    if (v.correccion() != null && !v.correccion().isBlank()) correcciones.add(v.correccion());
                }
            } catch (Exception e) {
                // Un critico caido NO bloquea la pregunta: se registra y se sigue. Preferimos
                // una pregunta sin revisar a un alumno sin evaluacion por un fallo de red.
                log.warn("[COMITE-PREGUNTAS] Critico '{}' no respondio: {}", r.clave(), e.getMessage());
                puntuaciones.put(r.clave(), -1);
            }
        }

        boolean aprobada = problemas.isEmpty();
        emitir(emisor, aprobada ? "Pregunta verificada" : "Hay que mejorarla; rehaciendo");

        log.info("[COMITE-PREGUNTAS] {} · puntuaciones {}",
                aprobada ? "APROBADA" : "RECHAZADA (" + problemas.size() + " problemas)", puntuaciones);

        return new Dictamen(aprobada, problemas, String.join(" ", correcciones), puntuaciones);
    }

    private void emitir(BiConsumer<String, Object> emisor, String mensaje) {
        if (emisor != null) {
            emisor.accept("razonamiento", Map.of("paso", mensaje));
        }
    }

    private String recortar(String texto, int max) {
        if (texto == null) return "";
        return texto.length() > max ? texto.substring(0, max) : texto;
    }
}
