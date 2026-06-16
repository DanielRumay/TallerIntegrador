package com.example.tallerintegrador.service;

import com.example.tallerintegrador.repository.ArchivoPromptRepository;
import com.example.tallerintegrador.service.util.ByteArrayMultipartFile;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.GenerateContentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.example.tallerintegrador.agents.ContextSelectorAgent;
import com.example.tallerintegrador.service.RagRetrieverService.ChunkRelevante;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class SpikeService {

    private final GeminiService geminiService;
    private final PromptTemplateService promptTemplateService;
    private final MetricasEstandarizadasService metricasEstandarizadasService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final TikaExtractorService tikaExtractorService;
    private final ArchivoPromptRepository archivoPromptRepo;
    private final RagRetrieverService ragRetrieverService;
    private final ContextSelectorAgent contextSelectorAgent;
    private final PreguntaDedupService preguntaDedupService;

    // Verbos HOTS según Taxonomía Revisada de Bloom (Anderson & Krathwohl, 2001)
    private static final List<String> VERBOS_HOTS = List.of(
            //  EVALUAR (nivel 5) — Krathwohl Tabla 3
            // 5.1 Checking
            "comprueba", "comprobar", "comprobado",
            "detecta",   "detectar",  "detectado",
            "monitorea", "verifica",  "verificar",

            // 5.2 Critiquing / Judging
            "critica",   "criticar",  "criticado",
            "juzga",     "juzgar",    "juzgado",
            "juzgue",    "critique",

            // CREAR (nivel 6) Krathwohl Tabla 3
            // 6.1 Generating / Hypothesizing
            "genera",     "generar",    "generado",
            "hipótesis",  "hipotetiza", "hipotetizar",

            // 6.2 Planning / Designing
            "diseña",    "diseñar",   "diseñado",
            "planifica", "planificar",

            // 6.3 Producing / Constructing
            "produce",   "producir",  "construye", "construir",

            // ANALIZAR (nivel 4) — Krathwohl Tabla 3
            // 4.1 Differentiating
            "diferencia", "diferenciar", "distingue", "distinguir",

            // 4.2 Organizing
            "organiza",   "organizar",   "estructura", "estructurar",

            // 4.3 Attributing / Deconstructing
            "atribuye",   "atribuir",    "deconstruye"
    );

    //texto plano

    public List<Map<String, Object>> compare(
            String texto, String tipoPregunta, String nivelBloom, int cantidad) {

        List<Map<String, Object>> resultados = new ArrayList<>();

        for (String tecnica : List.of(
                PromptTemplateService.FEW_SHOT,
                PromptTemplateService.CHAIN_OF_THOUGHT,
                PromptTemplateService.STRUCTURED_OUTPUT)) {
            try {
                resultados.add(ejecutarTecnica(tecnica, tipoPregunta, nivelBloom, texto, cantidad));
                Thread.sleep(2000);
            } catch (Exception e) {
                log.error("Error con técnica {}: {}", tecnica, e.getMessage());
                resultados.add(Map.of("tecnica", tecnica, "error", e.getMessage()));
            }
        }
        return resultados;
    }

    public Map<String, Object> ejecutarTecnica(
            String tecnica, String tipoPregunta,
            String nivelBloom, String texto, int cantidad) {
        return ejecutarTecnica(tecnica, tipoPregunta, nivelBloom, texto, cantidad, null, null);
    }

    public Map<String, Object> ejecutarTecnica(
            String tecnica, String tipoPregunta,
            String nivelBloom, String texto, int cantidad, String userEmail, String mongoId) {

        Usuario usuario = preguntaDedupService.obtenerUsuarioPorEmail(userEmail);
        Long usuarioId = usuario != null ? usuario.getId() : null;
        List<String> preguntasEvitar = preguntaDedupService.obtenerPreguntasEvitar(userEmail, mongoId);

        List<Object> finalPreguntas = new ArrayList<>();
        List<String> avoidList = new ArrayList<>(preguntasEvitar);

        int attempts = 0;
        int targetCantidad = cantidad;

        long startTime = System.currentTimeMillis();
        int inputTokens = 0, outputTokens = 0, totalTokens = 0;

        Map<String, Object> bloom = new LinkedHashMap<>();
        Map<String, Object> leccion = null;

        while (finalPreguntas.size() < targetCantidad && attempts < 3) {
            attempts++;
            int needed = targetCantidad - finalPreguntas.size();

            String prompt = promptTemplateService.build(tecnica, tipoPregunta, nivelBloom, texto, needed, avoidList);

            // Obtenemos la respuesta completa de Gemini
            var responseObj = geminiService.askGemini(prompt);

            var optionalMetadata = responseObj.usageMetadata();
            if (optionalMetadata != null && optionalMetadata.isPresent()) {
                var metadata = optionalMetadata.get();
                inputTokens  += metadata.promptTokenCount().orElse(0);
                outputTokens += metadata.candidatesTokenCount().orElse(0);
                totalTokens  += metadata.totalTokenCount().orElse(0);
            }

            String respuesta = responseObj.text();
            String jsonLimpio = cleanJsonString(respuesta);

            if (bloom.isEmpty()) {
                bloom.putAll(extraerBloomDelJson(jsonLimpio, tecnica));
            }
            if (leccion == null) {
                leccion = extraerLeccionDelJson(jsonLimpio);
            }

            List<Object> generated = extraerPreguntasDelJson(jsonLimpio);
            for (Object p : generated) {
                if (p instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> preguntaMap = (Map<String, Object>) p;
                    String enunciado = (String) preguntaMap.get("enunciado");
                    if (enunciado != null && !enunciado.trim().isEmpty()) {
                        if (finalPreguntas.size() < targetCantidad) {
                            if (!avoidList.contains(enunciado) && !preguntaDedupService.esPreguntaSimilar(enunciado, usuarioId, avoidList)) {
                                finalPreguntas.add(p);
                                avoidList.add(enunciado);
                            } else {
                                log.warn("[DEDUP-SPIKE] Pregunta rechazada por similitud o duplicado exacto: {}", enunciado);
                            }
                        }
                    }
                }
            }
        }

        // Fallback si tras 3 intentos no se completaron preguntas
        if (finalPreguntas.size() < targetCantidad && attempts >= 3) {
            int needed = targetCantidad - finalPreguntas.size();
            log.warn("[DEDUP-SPIKE] Fallback de deduplicación: generando {} pregunta(s) restante(s) sin restricciones vectoriales.", needed);
            String prompt = promptTemplateService.build(tecnica, tipoPregunta, nivelBloom, texto, needed);
            var responseObj = geminiService.askGemini(prompt);
            String respuesta = responseObj.text();
            String jsonLimpio = cleanJsonString(respuesta);
            finalPreguntas.addAll(extraerPreguntasDelJson(jsonLimpio));
            if (bloom.isEmpty()) {
                bloom.putAll(extraerBloomDelJson(jsonLimpio, tecnica));
            }
            if (leccion == null) {
                leccion = extraerLeccionDelJson(jsonLimpio);
            }
        }

        long endTime = System.currentTimeMillis();
        long latenciaMs = endTime - startTime;

        postProcesarPreguntas(finalPreguntas, tipoPregunta);

        // CREAMOS EL MAPA DE MÉTRICAS TÉCNICAS
        Map<String, Object> metricasRendimiento = Map.of(
                "latencia_segundos", latenciaMs / 1000.0,
                "input_tokens", inputTokens,
                "output_tokens", outputTokens,
                "total_tokens", totalTokens
        );

        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("tecnica",            tecnica);
        resultado.put("tipo_pregunta",      tipoPregunta);
        resultado.put("nivel_bloom_obj",    nivelBloom != null ? nivelBloom : "Auto");
        resultado.put("preguntas",          finalPreguntas);
        resultado.put("metricas_objetivas", calcularMetricasObjetivas(finalPreguntas, tipoPregunta, texto));
        resultado.put("metricas_rendimiento", metricasRendimiento);
        resultado.putAll(bloom);
        if (leccion != null) {
            resultado.put("leccion", leccion);
        }

        return resultado;
    }

    // pdf directo

    public List<Map<String, Object>> compareConPdfs(
            List<MultipartFile> archivos, String tipoPregunta,
            String nivelBloom, int cantidad) {

        List<Map<String, Object>> resultados = new ArrayList<>();

        for (String tecnica : List.of(
                PromptTemplateService.FEW_SHOT,
                PromptTemplateService.CHAIN_OF_THOUGHT,
                PromptTemplateService.STRUCTURED_OUTPUT)) {
            try {
                resultados.add(ejecutarTecnicaConPdfs(tecnica, tipoPregunta, nivelBloom, archivos, cantidad));
                Thread.sleep(2000);
            } catch (Exception e) {
                log.error("Error con técnica {}: {}", tecnica, e.getMessage());
                resultados.add(Map.of("tecnica", tecnica, "error", e.getMessage()));
            }
        }
        return resultados;
    }

    public Map<String, Object> ejecutarTecnicaConPdfs(
            String tecnica, String tipoPregunta,
            String nivelBloom, List<MultipartFile> archivos, int cantidad) throws Exception {

        String prompt = promptTemplateService.build(
                tecnica, tipoPregunta, nivelBloom,
                "[Los documentos PDF están adjuntos. Analízalos directamente.]",
                cantidad);

        //crono
        long startTime = System.currentTimeMillis();

        // Obtenemos la respuesta completa de Gemini
        var responseObj  = geminiService.askGeminiWithPdfs(prompt, archivos);

        // 2. DETENEMOS EL CRONÓMETRO
        long endTime = System.currentTimeMillis();
        long latenciaMs = endTime - startTime;

        // Extraemos el texto
        String respuesta = responseObj.text();
        String jsonLimpio = cleanJsonString(respuesta);

        // 3. EXTRAEMOS LOS TOKENS
        int inputTokens = 0, outputTokens = 0, totalTokens = 0;

        var optionalMetadata = responseObj.usageMetadata();

        if (optionalMetadata != null && optionalMetadata.isPresent()) {
            var metadata = optionalMetadata.get();

            // Usamos .orElse(0) para sacar el int del Optional (o poner 0 si no hay nada)
            inputTokens  = metadata.promptTokenCount().orElse(0);
            outputTokens = metadata.candidatesTokenCount().orElse(0);
            totalTokens  = metadata.totalTokenCount().orElse(0);
        }

        Map<String, Object> bloom     = extraerBloomDelJson(jsonLimpio, tecnica);
        List<Object>        preguntas = extraerPreguntasDelJson(jsonLimpio);
        postProcesarPreguntas(preguntas, tipoPregunta);

        //mapa de metricas tecnicas
        Map<String, Object> metricasRendimiento = Map.of(
                "latencia_segundos", latenciaMs / 1000.0,
                "input_tokens", inputTokens,
                "output_tokens", outputTokens,
                "total_tokens", totalTokens
        );

        String textoRealDelPdf = tikaExtractorService.extractTextFromMultipleFiles(archivos);

        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("tecnica",            tecnica);
        resultado.put("tipo_pregunta",      tipoPregunta);
        resultado.put("nivel_bloom_obj",    nivelBloom != null ? nivelBloom : "Auto");
        resultado.put("preguntas",          preguntas);
        resultado.put("metricas_objetivas", calcularMetricasObjetivas(preguntas, tipoPregunta, textoRealDelPdf));
        resultado.put("metricas_rendimiento", metricasRendimiento);
        resultado.putAll(bloom);
        Map<String, Object> leccion = extraerLeccionDelJson(jsonLimpio);
        if (leccion != null) {
            resultado.put("leccion", leccion);
        }

        return resultado;
    }

    //metricas objetivas
    private Map<String, Object> calcularMetricasObjetivas(List<Object> preguntas, String tipoPregunta, String textoBase) {
        int total         = preguntas.size();
        int conVerbosHots = 0;
        int conRubrica    = 0;
        int longitudTotal = 0;

        StringBuilder textoParaMetricas = new StringBuilder();

        for (Object p : preguntas) {
            @SuppressWarnings("unchecked")
            Map<String, Object> pregunta = (Map<String, Object>) p;
            String enunciado = String.valueOf(pregunta.getOrDefault("enunciado", "")).toLowerCase();
            String respuesta = String.valueOf(pregunta.getOrDefault("opciones_o_respuesta", "")).toLowerCase();

            if (VERBOS_HOTS.stream().anyMatch(enunciado::contains)) conVerbosHots++;

            if ("ABIERTA".equals(tipoPregunta)) {
                if (respuesta.contains("rúbrica") || respuesta.contains("rubrica")
                        || respuesta.contains("nivel excelente") || respuesta.contains("nivel adecuado")
                        || respuesta.contains("puntos") || respuesta.contains("nivel:")) {
                    conRubrica++;
                }
            }
            longitudTotal += enunciado.length();
            textoParaMetricas.append(enunciado).append(" ");
        }

        if (total == 0) return Map.of("total_preguntas", 0, "pct_verbos_hots", "0%", "pct_con_rubrica", "N/A", "longitud_prom_chars", 0);

        String pctRubrica = "ABIERTA".equals(tipoPregunta)
                ? Math.round((double) conRubrica / total * 100) + "%"
                : "N/A (no aplica para " + tipoPregunta + ")";

        String textoAnalisis = textoParaMetricas.toString();

        // 1. Cálculos de texto
        double lecturabilidad = metricasEstandarizadasService.calcularLecturabilidad(textoAnalisis);
        double ttr = metricasEstandarizadasService.calcularTTR(textoAnalisis);

        //CÁLCULO DE SIMILITUD DE COSENO (La magia de los Embeddings)
        double similitudSemantica = 0.0;
        // Solo lo calculamos si hay un texto real (ignoramos el mensaje de "PDFs adjuntos")
        if (textoBase != null && !textoBase.contains("PDF están adjuntos") && !textoBase.trim().isEmpty()) {
            List<Float> vectorBase = geminiService.getEmbeddings(textoBase);
            List<Float> vectorPreguntas = geminiService.getEmbeddings(textoAnalisis);
            similitudSemantica = metricasEstandarizadasService.calcularSimilitudCoseno(vectorBase, vectorPreguntas);
        }

        return Map.of(
                "total_preguntas",     total,
                "pct_verbos_hots",     Math.round((double) conVerbosHots / total * 100) + "%",
                "pct_con_rubrica",     pctRubrica,
                "longitud_prom_chars", longitudTotal / total,
                "lecturabilidad_fernandez_huerta", lecturabilidad,
                "riqueza_lexica_ttr", ttr,
                "similitud_semantica", similitudSemantica // ✅ AGREGADO AL JSON
        );
    }


    private List<Object> extraerPreguntasDelJson(String jsonLimpio) {
        try {
            JsonNode root      = mapper.readTree(jsonLimpio);
            JsonNode preguntas = root.path("preguntas");

            if (!preguntas.isMissingNode() && preguntas.isArray()) {
                List<Object> lista = new ArrayList<>();
                for (JsonNode pregunta : preguntas) {
                    lista.add(mapper.convertValue(pregunta, Map.class));
                }
                return lista;
            }
        } catch (Exception e) {
            log.warn("No se pudieron parsear las preguntas: {}", e.getMessage());
        }
        return List.of();
    }

    private Map<String, Object> extraerLeccionDelJson(String jsonLimpio) {
        try {
            JsonNode root = mapper.readTree(jsonLimpio);
            JsonNode leccionNode = root.path("leccion");
            if (!leccionNode.isMissingNode() && leccionNode.isObject()) {
                Map<String, Object> leccion = mapper.convertValue(leccionNode, Map.class);
                postProcesarLeccion(leccion);
                return leccion;
            }
        } catch (Exception e) {
            log.warn("No se pudo parsear el JSON de leccion: {}", e.getMessage());
        }
        return null;
    }

    private Map<String, Object> extraerBloomDelJson(String jsonLimpio, String tecnica) {
        try {
            JsonNode root = mapper.readTree(jsonLimpio);
            JsonNode eval = root.path("evaluacion_bloom");

            if (!eval.isMissingNode()) {
                return Map.of(
                        "nivel_bloom",       eval.path("nivel_bloom").asText("N/A"),
                        "nivel_bloom_orden", eval.path("nivel_bloom_orden").asInt(0),
                        "es_hots",           eval.path("es_hots").asBoolean(false)
                );
            } else {
                return Map.of("nivel_bloom", "No encontrado en JSON");
            }
        } catch (Exception e) {
            log.warn("No se pudo parsear el JSON de Bloom para {}: {}", tecnica, e.getMessage());
            return Map.of("nivel_bloom", "Error de parseo");
        }
    }

    private String cleanJsonString(String raw) {
        if (raw == null) return "{}";

        raw = raw.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();

        int startIndex = raw.indexOf("{");
        int endIndex   = raw.lastIndexOf("}");

        if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            raw = raw.substring(startIndex, endIndex + 1);
        } else {
            log.warn("No se encontraron llaves de JSON en la respuesta.");
            return "{}";
        }
        return raw;
    }

    public Map<String, Object> ejecutarTecnicaConPdfId(
            String mongoId, String tipo, int cantidad, String tema) throws Exception {
        return ejecutarTecnicaConPdfId(mongoId, tipo, cantidad, tema, null);
    }

    public Map<String, Object> ejecutarTecnicaConPdfId(
            String mongoId, String tipo, int cantidad, String tema, String userEmail) throws Exception {

        String tecnica = PromptTemplateService.STRUCTURED_OUTPUT;
        String nivelBloom = "5";

        // Si no se especifica tema de RAG, usamos el nombre del archivo limpio como criterio
        String temaBusqueda = tema;
        if (temaBusqueda == null || temaBusqueda.trim().isEmpty()) {
            var archivo = archivoPromptRepo.findById(mongoId)
                    .orElseThrow(() -> new RuntimeException("Archivo no encontrado en Mongo"));
            temaBusqueda = archivo.getNombre();
            if (temaBusqueda.contains(".")) {
                temaBusqueda = temaBusqueda.substring(0, temaBusqueda.lastIndexOf("."));
            }
        }

        log.info("[SPIKE-RAG] Recuperando chunks RAG para tema '{}' en archivo {}", temaBusqueda, mongoId);
        List<ChunkRelevante> chunks = List.of();
        try {
            chunks = ragRetrieverService.recuperar(temaBusqueda, mongoId);
        } catch (Exception e) {
            log.warn("[SPIKE-RAG] Falló recuperación de chunks, aplicando fallback: {}", e.getMessage());
        }

        String contexto;
        if (chunks != null && !chunks.isEmpty()) {
            log.info("[SPIKE-RAG] Chunks recuperados: {}. Extrayendo contexto filtrado.", chunks.size());
            contexto = contextSelectorAgent.seleccionarContexto(chunks, nivelBloom, tipo);
        } else {
            log.warn("[SPIKE-RAG] No se hallaron chunks RAG o no está indexado. Leyendo texto completo.");
            var archivoEntity = archivoPromptRepo.findById(mongoId).get();
            MultipartFile file = new ByteArrayMultipartFile(
                    archivoEntity.getArchivoFisico(),
                    archivoEntity.getNombre(),
                    archivoEntity.getTipo()
            );
            contexto = tikaExtractorService.extractTextFromMultipleFiles(List.of(file));
        }

        return ejecutarTecnica(tecnica, tipo, nivelBloom, contexto, cantidad, userEmail, mongoId);
    }

    public void ejecutarTecnicaConPdfIdStream(
            String mongoId, String tipo, int cantidad, String tema,
            SseEmitter emitter) throws Exception {
        ejecutarTecnicaConPdfIdStream(mongoId, tipo, cantidad, tema, emitter, null);
    }

    public void ejecutarTecnicaConPdfIdStream(
            String mongoId, String tipo, int cantidad, String tema,
            SseEmitter emitter, String userEmail) throws Exception {

        String tecnica    = PromptTemplateService.STRUCTURED_OUTPUT;
        String nivelBloom = "5";

        String temaBusqueda = tema;
        if (temaBusqueda == null || temaBusqueda.trim().isEmpty()) {
            var archivo = archivoPromptRepo.findById(mongoId).orElse(null);
            if (archivo != null) {
                temaBusqueda = archivo.getNombre();
                if (temaBusqueda.contains(".")) {
                    temaBusqueda = temaBusqueda.substring(0, temaBusqueda.lastIndexOf("."));
                }
            } else {
                temaBusqueda = "conceptos principales";
            }
        }

        List<ChunkRelevante> chunks = List.of();
        try {
            chunks = ragRetrieverService.recuperar(temaBusqueda, mongoId);
        } catch (Exception e) {
            log.warn("[SPIKE-RAG-STREAM] Falló RAG, aplicando fallback: {}", e.getMessage());
        }

        String contexto;
        if (chunks != null && !chunks.isEmpty()) {
            contexto = contextSelectorAgent.seleccionarContexto(chunks, nivelBloom, tipo);
        } else {
            var archivoEntity = archivoPromptRepo.findById(mongoId)
                    .orElseThrow(() -> new RuntimeException("Archivo no encontrado"));
            MultipartFile file = new ByteArrayMultipartFile(
                    archivoEntity.getArchivoFisico(),
                    archivoEntity.getNombre(),
                    archivoEntity.getTipo()
            );
            contexto = tikaExtractorService.extractTextFromMultipleFiles(List.of(file));
        }

        List<String> preguntasEvitar = preguntaDedupService.obtenerPreguntasEvitar(userEmail, mongoId);
        String prompt = promptTemplateService.build(tecnica, tipo, nivelBloom, contexto, cantidad, preguntasEvitar);
        Iterable<GenerateContentResponse> streamResponse = geminiService.askGeminiStream(prompt);

        long startTime = System.currentTimeMillis();
        StringBuilder fullResponse = new StringBuilder();
        int[] tokens = {0, 0, 0};

        for (GenerateContentResponse chunk : streamResponse) {
            String text = chunk.text();
            if (text != null && !text.isEmpty()) {
                fullResponse.append(text);
                emitter.send(SseEmitter.event().name("chunk").data(text));
            }
            var meta = chunk.usageMetadata();
            if (meta != null && meta.isPresent()) {
                tokens[0] = meta.get().promptTokenCount().orElse(0);
                tokens[1] = meta.get().candidatesTokenCount().orElse(0);
                tokens[2] = meta.get().totalTokenCount().orElse(0);
            }
        }

        long latenciaMs = System.currentTimeMillis() - startTime;
        String jsonLimpio = cleanJsonString(fullResponse.toString());
        Map<String, Object> bloom     = extraerBloomDelJson(jsonLimpio, tecnica);
        List<Object>        preguntas = extraerPreguntasDelJson(jsonLimpio);
        postProcesarPreguntas(preguntas, tipo);

        Map<String, Object> metricasRendimiento = Map.of(
                "latencia_segundos", latenciaMs / 1000.0,
                "input_tokens",  tokens[0],
                "output_tokens", tokens[1],
                "total_tokens",  tokens[2]
        );

        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("tecnica",              tecnica);
        resultado.put("tipo_pregunta",        tipo);
        resultado.put("nivel_bloom_obj",      nivelBloom);
        resultado.put("preguntas",            preguntas);
        resultado.put("metricas_objetivas",   calcularMetricasObjetivas(preguntas, tipo, contexto));
        resultado.put("metricas_rendimiento", metricasRendimiento);
        resultado.putAll(bloom);
        Map<String, Object> leccion = extraerLeccionDelJson(jsonLimpio);
        if (leccion != null) {
            resultado.put("leccion", leccion);
        }

        String jsonResultado = mapper.writeValueAsString(resultado);
        emitter.send(SseEmitter.event().name("result").data(jsonResultado));
        emitter.complete();
    }

    private void postProcesarPreguntas(List<Object> preguntas, String tipoPregunta) {
        // Las ilustraciones se cargan de forma asíncrona / bajo demanda en el cliente
        // para lograr un inicio instantáneo del quiz sin retrasar la respuesta del servidor.
    }

    private void postProcesarLeccion(Map<String, Object> leccion) {
        // Las ilustraciones se cargan de forma asíncrona / bajo demanda en el cliente
        // para lograr un inicio instantáneo de la videolección sin retrasar la respuesta del servidor.
    }

    public String generarImagenDirecta(String prompt) {
        log.info("[SPIKE-DEMAND] Generando imagen para prompt: {}", prompt);
        return geminiService.generarImagenConImagen3(prompt);
    }
}