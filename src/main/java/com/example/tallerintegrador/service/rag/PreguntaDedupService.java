package com.example.tallerintegrador.service.rag;
import com.example.tallerintegrador.service.metricas.TelemetriaIAService;

import com.example.tallerintegrador.entidades.postgres.TipoEventoIA;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.repository.UserRepository;
import com.example.tallerintegrador.repository.RespuestaUsuarioRepository;
import com.example.tallerintegrador.repository.SemanaRepository;
import com.example.tallerintegrador.repository.MaterialRepository;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.embedding.Embedding;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Slf4j
@Service
public class PreguntaDedupService {

    private final UserRepository userRepository;
    private final RespuestaUsuarioRepository respuestaUsuarioRepository;
    private final SemanaRepository semanaRepository;
    private final MaterialRepository materialRepository;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final TelemetriaIAService telemetriaIAService;
    private final com.example.tallerintegrador.service.ia.GeminiService geminiService;

    public PreguntaDedupService(
            UserRepository userRepository,
            RespuestaUsuarioRepository respuestaUsuarioRepository,
            SemanaRepository semanaRepository,
            MaterialRepository materialRepository,
            EmbeddingModel embeddingModel,
            @Qualifier("questionsEmbeddingStore") EmbeddingStore<TextSegment> embeddingStore,
            TelemetriaIAService telemetriaIAService,
            com.example.tallerintegrador.service.ia.GeminiService geminiService) {
        this.userRepository = userRepository;
        this.respuestaUsuarioRepository = respuestaUsuarioRepository;
        this.semanaRepository = semanaRepository;
        this.materialRepository = materialRepository;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.telemetriaIAService = telemetriaIAService;
        this.geminiService = geminiService;
    }

    /**
     * Segunda etapa: decide si dos preguntas parecidas son en realidad la misma.
     *
     * Ante un fallo devuelve FALSE, es decir, acepta la pregunta. Es la eleccion menos mala:
     * si el modelo no responde, el alumno recibe como mucho una pregunta repetida; devolver
     * true le dejaria sin pregunta por un problema de red. Queda registrado igualmente.
     */
    private boolean confirmarDuplicadoConModelo(String nueva, String existente) {
        try {
            String prompt = """
                    Estas dos preguntas se harán a un mismo estudiante. Decide si son la MISMA
                    pregunta formulada de otra manera, o si son preguntas DISTINTAS que él
                    respondería de forma diferente.

                    Son la MISMA si un estudiante daría esencialmente la misma respuesta.
                    Son DISTINTAS si comparten el tema pero preguntan por aspectos diferentes
                    (por ejemplo, "qué es" frente a "dónde ocurre", o "causas" frente a
                    "consecuencias").

                    Responde ÚNICAMENTE con una palabra: MISMA o DISTINTAS.

                    PREGUNTA A: %s
                    PREGUNTA B: %s
                    """.formatted(nueva, existente);

            String respuesta = geminiService.askGemini(prompt).text();
            if (respuesta == null) return false;
            return respuesta.trim().toUpperCase().startsWith("MISMA");
        } catch (Exception e) {
            log.warn("[DEDUP] No se pudo confirmar el duplicado con el modelo: {}", e.getMessage());
            return false;
        }
    }

    public Usuario obtenerUsuarioPorEmail(String email) {
        if (email == null || "anonymousUser".equals(email) || email.trim().isEmpty())
            return null;
        return userRepository.findByCorreo(email).orElse(null);
    }

    public Long obtenerSemanaIdPorMongoId(String mongoId) {
        if (mongoId == null || mongoId.trim().isEmpty())
            return null;
        var semanaOpt = semanaRepository.findByMongoId(mongoId);
        if (semanaOpt.isPresent()) {
            return semanaOpt.get().getId();
        }
        var materialOpt = materialRepository.findByMongoId(mongoId);
        if (materialOpt.isPresent()) {
            return materialOpt.get().getSemana().getId();
        }
        return null;
    }

    public List<String> obtenerPreguntasEvitar(String email, String mongoId) {
        Usuario usuario = obtenerUsuarioPorEmail(email);
        if (usuario == null)
            return List.of();
        Long semanaId = obtenerSemanaIdPorMongoId(mongoId);
        if (semanaId == null)
            return List.of();

        // Limitar a las últimas 50 preguntas para no saturar el prompt
        return respuestaUsuarioRepository.findByUsuarioIdAndPreguntaSemanaId(usuario.getId(), semanaId)
                .stream()
                .map(ru -> ru.getPregunta().getPregunta())
                .filter(Objects::nonNull)
                .distinct()
                .limit(50)
                .toList();
    }

    /**
     * DEDUPLICACION EN DOS ETAPAS.
     *
     * El problema de una sola etapa: con un unico umbral hay que elegir entre dejar pasar
     * duplicados o rechazar preguntas legitimas, y no hay valor que evite ambas cosas.
     *
     *   - Con 0,95 (el valor anterior), "Por que las plantas necesitan luz?" y "Que funcion
     *     cumple la luz solar en las plantas?" rondan 0,85 y PASABAN como distintas. Son la
     *     misma pregunta.
     *   - Bajando a 0,85 se cazan esas, pero tambien se rechazan preguntas que comparten
     *     vocabulario sin ser la misma ("Que es la fotosintesis?" vs "Donde ocurre la
     *     fotosintesis?").
     *
     * La solucion del estado del arte es separar RECUPERAR de DECIDIR: una primera etapa
     * barata y generosa que trae candidatos, y una segunda que los examina de cerca. En la
     * literatura de recuperacion esa segunda etapa suele ser un cross-encoder afinado sobre
     * pares de preguntas duplicadas; aqui se usa el modelo que ya esta integrado, que cumple
     * la misma funcion: ver las DOS preguntas juntas en vez de comparar dos vectores
     * calculados por separado.
     */

    /** Por encima de esto se rechaza sin preguntar: es practicamente el mismo texto. */
    public static final double UMBRAL_CERTEZA = 0.95;

    /** Por debajo de esto ni siquiera se considera candidata. 0,85 es la referencia habitual. */
    public static final double UMBRAL_CANDIDATO = 0.82;

    /** Compatibilidad: algun codigo antiguo referencia este nombre. */
    public static final double UMBRAL_SIMILITUD = UMBRAL_CERTEZA;

    /**
     * Compara una pregunta candidata contra otras que AÚN NO ESTÁN GUARDADAS.
     *
     * EL HUECO QUE CIERRA. `esPreguntaSimilar` busca en Qdrant, y las preguntas de un mismo
     * lote todavía no están ahí: se indexan cuando el alumno termina el examen. Así que dos
     * preguntas generadas en la MISMA llamada, si eran reformulaciones una de otra, pasaban
     * las dos: el filtro de texto exacto no las veía por estar escritas distinto, y el
     * vectorial no las veía por no existir aún en el índice.
     *
     * Es el caso más probable de todos, además: cuando se le piden cinco preguntas a un
     * modelo sobre un mismo fragmento, la tendencia natural es que dos se parezcan.
     *
     * Aquí no hay Qdrant: se calculan los vectores al vuelo y se compara el coseno en
     * memoria. Con cinco preguntas son cinco vectores y diez comparaciones aritméticas, sin
     * coste apreciable. Se aplica la misma escalera de dos etapas que el filtro histórico.
     *
     * @param yaAceptadas preguntas admitidas antes que esta en el mismo lote
     * @return true si la candidata repite alguna de ellas
     */
    /**
     * Vectores ya calculados en esta ejecución, por texto exacto.
     *
     * POR QUÉ. Generar una prueba embebía el MISMO texto varias veces: la comparación dentro
     * del lote recalculaba el vector de cada pregunta ya aceptada en cada comparación —O(n²)
     * llamadas— y luego se volvía a calcular el de la candidata para guardarla. En un log real
     * se ve la misma pregunta pedida a Gemini dos veces con segundo y medio de diferencia.
     *
     * Cada una de esas llamadas es red, dinero y ~400 ms de espera del alumno, y todas
     * devuelven exactamente lo mismo: el embedding de un texto es determinista.
     *
     * Se acota el tamaño para que un proceso largo no crezca sin límite; al llenarse se vacía
     * entero, que es lo más simple y aquí basta: el objetivo es reutilizar dentro de una misma
     * generación, no mantener una caché a largo plazo.
     */
    private static final int MAX_VECTORES_CACHE = 500;
    private final java.util.Map<String, float[]> cacheVectores = new java.util.concurrent.ConcurrentHashMap<>();

    private float[] vectorDe(String texto) {
        float[] guardado = cacheVectores.get(texto);
        if (guardado != null) return guardado;

        float[] nuevo = embeddingModel.embed(texto).content().vector();
        if (cacheVectores.size() >= MAX_VECTORES_CACHE) cacheVectores.clear();
        cacheVectores.put(texto, nuevo);
        return nuevo;
    }

    public boolean repiteAlgunaDelLote(String candidata, List<String> yaAceptadas) {
        if (candidata == null || candidata.isBlank() || yaAceptadas == null || yaAceptadas.isEmpty()) {
            return false;
        }
        try {
            float[] vectorCandidata = vectorDe(candidata);

            for (String aceptada : yaAceptadas) {
                if (aceptada == null || aceptada.isBlank()) continue;

                double similitud = coseno(vectorCandidata, vectorDe(aceptada));

                if (similitud >= UMBRAL_CERTEZA) {
                    log.warn("[DEDUP-LOTE] Repetida dentro del mismo examen (score={}): '{}'", similitud, candidata);
                    return true;
                }
                if (similitud >= UMBRAL_CANDIDATO && confirmarDuplicadoConModelo(candidata, aceptada)) {
                    log.warn("[DEDUP-LOTE] Reformulación detectada dentro del mismo examen (score={}): '{}'",
                            similitud, candidata);
                    return true;
                }
            }
        } catch (Exception e) {
            // Igual que en el filtro histórico: ante un fallo se acepta. Una pregunta repetida
            // es peor que nada, pero mucho menos grave que un examen que no se genera.
            log.warn("[DEDUP-LOTE] No se pudo comparar dentro del lote: {}", e.getMessage());
        }
        return false;
    }

    /** Similitud de coseno entre dos vectores de la misma dimensión. */
    private double coseno(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0.0;
        double producto = 0, normaA = 0, normaB = 0;
        for (int i = 0; i < a.length; i++) {
            producto += a[i] * b[i];
            normaA += a[i] * a[i];
            normaB += b[i] * b[i];
        }
        if (normaA == 0 || normaB == 0) return 0.0;
        return producto / (Math.sqrt(normaA) * Math.sqrt(normaB));
    }

    public boolean esPreguntaSimilar(String preguntaTexto, Long usuarioId, List<String> ultimasPreguntas) {
        if (usuarioId == null || preguntaTexto == null || preguntaTexto.trim().isEmpty())
            return false;

        // Coincidencia exacta: se resuelve sin gastar una llamada al modelo de embeddings.
        if (ultimasPreguntas != null && ultimasPreguntas.contains(preguntaTexto)) {
            log.info("[DEDUP] Coincidencia exacta de texto en el historial del alumno {}", usuarioId);
            telemetriaIAService.registrar(TipoEventoIA.DEDUP_CANDIDATA, "DUPLICADO_EXACTO", usuarioId, 1.0);
            return true;
        }

        try {
            Response<Embedding> embResponse = embeddingModel.embed(preguntaTexto);
            Embedding queryEmbedding = embResponse.content();

            // El aislamiento por alumno y tipo se resuelve como payload filter en Qdrant,
            // no filtrando en memoria un lote sobredimensionado de resultados ajenos.
            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .filter(metadataKey("tipo").isEqualTo("pregunta")
                            .and(metadataKey("usuarioId").isEqualTo(String.valueOf(usuarioId))))
                    .maxResults(5)
                    .minScore(UMBRAL_CANDIDATO)
                    .build();

            var matches = embeddingStore.search(searchRequest).matches();

            if (!matches.isEmpty()) {
                var match = matches.get(0);
                String candidata = match.embedded().text();

                // Etapa 1 — certeza: tan parecidas que no hace falta consultar a nadie.
                if (match.score() >= UMBRAL_CERTEZA) {
                    log.info("[DEDUP] Duplicado claro (score={}) para el alumno {}: '{}'",
                            match.score(), usuarioId, candidata);
                    telemetriaIAService.registrar(
                            TipoEventoIA.DEDUP_CANDIDATA, "DUPLICADO_VECTORIAL", usuarioId, match.score());
                    return true;
                }

                // Etapa 2 — zona dudosa: se le muestran las DOS preguntas al modelo.
                //
                // Aquí es donde caían los duplicados reales con el umbral anterior. Comparar
                // dos vectores calculados por separado no distingue "misma pregunta con otras
                // palabras" de "mismo tema, pregunta distinta"; verlas juntas, sí.
                boolean sonLaMisma = confirmarDuplicadoConModelo(preguntaTexto, candidata);
                telemetriaIAService.registrar(TipoEventoIA.DEDUP_CANDIDATA,
                        sonLaMisma ? "DUPLICADO_CONFIRMADO" : "SIMILAR_PERO_DISTINTA",
                        usuarioId, match.score());

                if (sonLaMisma) {
                    log.info("[DEDUP] Duplicado confirmado por el modelo (score={}): '{}'",
                            match.score(), candidata);
                    return true;
                }
                log.info("[DEDUP] Parecidas (score={}) pero distintas según el modelo; se acepta", match.score());
                return false;
            }

            telemetriaIAService.registrar(TipoEventoIA.DEDUP_CANDIDATA, "ACEPTADA", usuarioId, 0.0);
        } catch (Exception e) {
            log.error("Error al buscar similitud de pregunta en Qdrant: {}", e.getMessage());
            // Un fallo del filtro no debe bloquear la generación: se acepta y se deja constancia.
            telemetriaIAService.registrar(TipoEventoIA.DEDUP_CANDIDATA, "ERROR_FILTRO", usuarioId);
        }
        return false;
    }
}
