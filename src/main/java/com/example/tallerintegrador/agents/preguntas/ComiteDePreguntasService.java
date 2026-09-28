package com.example.tallerintegrador.agents.preguntas;

import com.example.tallerintegrador.service.util.EnunciadoGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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
    private final CriticoDeLote criticoContenidoLote;
    private final CriticoDeLote criticoPedagogicoLote;
    private final CriticoDeLote criticoFormaLote;

    public ComiteDePreguntasService(
            @Qualifier("criticoContenido") CriticoDePreguntas criticoContenido,
            @Qualifier("criticoPedagogico") CriticoDePreguntas criticoPedagogico,
            @Qualifier("criticoForma") CriticoDePreguntas criticoForma,
            @Qualifier("criticoContenidoLote") CriticoDeLote criticoContenidoLote,
            @Qualifier("criticoPedagogicoLote") CriticoDeLote criticoPedagogicoLote,
            @Qualifier("criticoFormaLote") CriticoDeLote criticoFormaLote) {
        this.criticoContenido = criticoContenido;
        this.criticoPedagogico = criticoPedagogico;
        this.criticoForma = criticoForma;
        this.criticoContenidoLote = criticoContenidoLote;
        this.criticoPedagogicoLote = criticoPedagogicoLote;
        this.criticoFormaLote = criticoFormaLote;
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

    /**
     * Revisa un lote completo con UNA llamada por critico, en vez de tres por reactivo.
     *
     * POR QUE. Con diez preguntas, revisar de una en una son treinta llamadas: es lo que hacia
     * inviable conectar el comite sin romper el limite de latencia de la primera pregunta ni la
     * cuota de IA de un aula. En lote son tres, y como los criticos corren en paralelo el costo
     * en tiempo es el de una sola.
     *
     * Las guardas deterministas siguen aplicandose reactivo por reactivo ANTES de gastar nada:
     * un enunciado que cita el documento se rechaza sin preguntarle al modelo.
     *
     * Si un critico falla o devuelve menos veredictos de los pedidos, los reactivos sin
     * veredicto NO se rechazan: se marcan con puntuacion -1 y siguen. Preferimos una pregunta
     * sin revisar a un alumno sin evaluacion.
     *
     * @return dictamen por reactivo, en el mismo orden en que se recibieron
     */
    public List<Dictamen> revisarLote(
            List<ReactivoARevisar> reactivos,
            String contexto,
            String nivelBloom,
            BiConsumer<String, Object> emisor) {

        if (reactivos == null || reactivos.isEmpty()) return List.of();

        List<String> problemasDeterministas = new ArrayList<>();
        List<Integer> aRevisar = new ArrayList<>();
        emitir(emisor, "Comprobando que las preguntas se entiendan por si solas");
        for (int i = 0; i < reactivos.size(); i++) {
            if (EnunciadoGuard.contieneReferenciaEstructural(reactivos.get(i).enunciado())) {
                problemasDeterministas.add(String.valueOf(i));
            } else {
                aRevisar.add(i);
            }
        }

        Map<Integer, List<String>> problemas = new LinkedHashMap<>();
        Map<Integer, List<String>> correcciones = new LinkedHashMap<>();
        Map<Integer, Map<String, Integer>> puntuaciones = new LinkedHashMap<>();
        Map<Integer, String> nivelesDeLectura = new LinkedHashMap<>();
        for (int i = 0; i < reactivos.size(); i++) {
            problemas.put(i, new ArrayList<>());
            correcciones.put(i, new ArrayList<>());
            puntuaciones.put(i, new LinkedHashMap<>());
        }
        for (String indice : problemasDeterministas) {
            int i = Integer.parseInt(indice);
            problemas.get(i).add("El enunciado remite a una parte del documento que el alumno no puede ver.");
            correcciones.get(i).add("Reescribe el enunciado para que sea autosuficiente: nada de "
                    + "'segun el punto N', 'en el texto anterior' ni referencias a secciones.");
            puntuaciones.get(i).put("estructura", 1);
        }

        if (!aRevisar.isEmpty()) {
            emitir(emisor, "Revisando contenido, nivel y redaccion de " + aRevisar.size() + " pregunta(s)");
            String listado = listado(reactivos, aRevisar);

            record RevisionLote(String clave, CriticoDeLote critico, String datos) {}
            List<RevisionLote> revisiones = new ArrayList<>();
            if (contexto != null && !contexto.isBlank()) {
                revisiones.add(new RevisionLote("contenido", criticoContenidoLote,
                        "MATERIAL DE ESTUDIO:\n" + recortar(contexto, 6000) + "\n\n" + listado));
            } else {
                log.info("[COMITE-LOTE] Sin material: se omite el critico de contenido");
            }
            revisiones.add(new RevisionLote("pedagogico", criticoPedagogicoLote,
                    "NIVEL DE BLOOM SOLICITADO: " + nivelBloom + "\n\n" + listado));
            revisiones.add(new RevisionLote("forma", criticoFormaLote, listado));

            List<CompletableFuture<Map.Entry<String, DictamenDeLote>>> tareas = revisiones.stream()
                    .map(r -> CompletableFuture.supplyAsync(() -> {
                        try {
                            return Map.entry(r.clave(), r.critico().revisar(r.datos()).content());
                        } catch (Exception e) {
                            log.warn("[COMITE-LOTE] Critico '{}' no respondio: {}", r.clave(), e.getMessage());
                            return Map.<String, DictamenDeLote>entry(r.clave(), new DictamenDeLote(List.of()));
                        }
                    }))
                    .toList();

            for (CompletableFuture<Map.Entry<String, DictamenDeLote>> tarea : tareas) {
                Map.Entry<String, DictamenDeLote> resultado;
                try {
                    resultado = tarea.join();
                } catch (Exception e) {
                    log.warn("[COMITE-LOTE] Un critico fallo al unir resultados: {}", e.getMessage());
                    continue;
                }
                String clave = resultado.getKey();
                List<VeredictoDeReactivo> veredictos = resultado.getValue() == null
                        ? List.of() : resultado.getValue().veredictos();
                Set<Integer> respondidos = new LinkedHashSet<>();
                for (VeredictoDeReactivo v : veredictos) {
                    int indice = v.id() - 1; // el modelo numera desde 1
                    if (indice < 0 || indice >= reactivos.size()) {
                        log.warn("[COMITE-LOTE] Critico '{}' devolvio un id fuera de rango: {}", clave, v.id());
                        continue;
                    }
                    respondidos.add(indice);
                    puntuaciones.get(indice).put(clave, v.puntuacion());
                    if (v.nivelLecturaEstimado() != null && !v.nivelLecturaEstimado().isBlank()) {
                        nivelesDeLectura.put(indice, v.nivelLecturaEstimado());
                    }
                    if (!v.aprobada() || v.puntuacion() < PUNTUACION_MINIMA) {
                        if (v.problema() != null && !v.problema().isBlank()) problemas.get(indice).add(v.problema());
                        if (v.correccion() != null && !v.correccion().isBlank()) correcciones.get(indice).add(v.correccion());
                    }
                }
                for (int indice : aRevisar) {
                    if (!respondidos.contains(indice)) {
                        // Sin veredicto no se rechaza: se deja constancia y la pregunta sigue.
                        puntuaciones.get(indice).put(clave, -1);
                    }
                }
            }
        }

        List<Dictamen> dictamenes = new ArrayList<>();
        int aprobadas = 0;
        for (int i = 0; i < reactivos.size(); i++) {
            boolean aprobada = problemas.get(i).isEmpty();
            if (aprobada) aprobadas++;
            dictamenes.add(new Dictamen(aprobada, List.copyOf(problemas.get(i)),
                    String.join(" ", correcciones.get(i)), Map.copyOf(puntuaciones.get(i))));
        }
        log.info("[COMITE-LOTE] {} de {} aprobadas · niveles de lectura estimados: {}",
                aprobadas, reactivos.size(), nivelesDeLectura);
        emitir(emisor, aprobadas == reactivos.size()
                ? "Preguntas verificadas"
                : "Se detectaron " + (reactivos.size() - aprobadas) + " pregunta(s) por mejorar");
        return dictamenes;
    }

    /** Un reactivo tal como lo ve el comite. */
    /**
     * @param promptImagen descripcion de la ilustracion que acompanara al reactivo, si la hay.
     *                     Importa porque en un reactivo visual la imagen puede regalar la
     *                     respuesta, y los criticos solo pueden detectarlo si la ven descrita:
     *                     cuando juzgan, la imagen todavia no existe.
     */
    public record ReactivoARevisar(String enunciado, String respuestaCorrecta, List<String> opciones,
                                   String promptImagen) {

        public ReactivoARevisar(String enunciado, String respuestaCorrecta, List<String> opciones) {
            this(enunciado, respuestaCorrecta, opciones, null);
        }
    }

    /** Arma el listado numerado que reciben los criticos. Empieza en 1 para el modelo. */
    private String listado(List<ReactivoARevisar> reactivos, List<Integer> indices) {
        StringBuilder sb = new StringBuilder("REACTIVOS A REVISAR (").append(indices.size()).append("):\n");
        for (int indice : indices) {
            ReactivoARevisar r = reactivos.get(indice);
            String opcionesTexto = (r.opciones() == null || r.opciones().isEmpty())
                    ? "(pregunta abierta, sin alternativas)"
                    : String.join(" | ", r.opciones());
            sb.append("\n--- id: ").append(indice + 1).append(" ---\n")
              .append("PREGUNTA: ").append(r.enunciado()).append("\n")
              .append("ALTERNATIVAS: ").append(opcionesTexto).append("\n")
              .append("RESPUESTA MARCADA COMO CORRECTA: ")
              .append(r.respuestaCorrecta() == null ? "(no declarada)" : r.respuestaCorrecta())
              .append("\n");
            if (r.promptImagen() != null && !r.promptImagen().isBlank()) {
                sb.append("ILUSTRACION QUE ACOMPANARA AL REACTIVO: ")
                  .append(r.promptImagen()).append("\n")
                  .append("ATENCION: si esa ilustracion muestra la respuesta, RECHAZA el reactivo. ")
                  .append("Una imagen que contiene la respuesta convierte la pregunta en lectura.\n");
            }
        }
        sb.append("\nDevuelve un veredicto por cada id listado, ni mas ni menos.");
        return sb.toString();
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
