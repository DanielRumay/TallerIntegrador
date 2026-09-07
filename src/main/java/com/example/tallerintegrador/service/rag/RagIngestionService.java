package com.example.tallerintegrador.service.rag;
import com.example.tallerintegrador.service.academico.ArchivoService;
import com.example.tallerintegrador.service.ia.GeminiService;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.concurrent.ExecutionException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * Pipeline de Ingesta (Patrón Pipeline):
 *   PDF/Archivo → Tika (texto) → Extraer Subtemas (Gemini) → Chunking (500 tok) → Embedding → Qdrant
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagIngestionService {

    private final TikaExtractorService   tikaExtractorService;
    private final SegmentadorDocumentoService segmentadorDocumentoService;
    private final ProgresoIngestaService progresoIngestaService;
    private final ResumidorJerarquicoService resumidorJerarquicoService;
    private final ChunkingService        chunkingService;
    private final EmbeddingModel         embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ArchivoService         archivoService;   // para guardar en Mongo también
    private final GeminiService          geminiService;
    private final QdrantClient           qdrantClient;
    private final ObjectMapper           objectMapper = new ObjectMapper();

    public void eliminarVectoresPorArchivoId(String archivoId) {
        log.info("Eliminando vectores del archivo: {}", archivoId);
        try {
            // A partir de qdrant-client 1.x, los tipos de filtro (Filter/Condition/
            // FieldCondition/Match) se reorganizaron de Points.* a Common.* — reflejan
            // que el mismo mecanismo de filtrado ahora se comparte entre Points y Query.
            Common.Filter filter = Common.Filter.newBuilder()
                    .addMust(Common.Condition.newBuilder()
                            .setField(Common.FieldCondition.newBuilder()
                                    .setKey("archivoId")
                                    .setMatch(Common.Match.newBuilder().setText(archivoId).build())
                                    .build())
                            .build())
                    .build();

            qdrantClient.deleteAsync(com.example.tallerintegrador.config.QdrantConfig.COLLECTION_NAME, filter).get();
            log.info("Vectores del archivo {} eliminados exitosamente de Qdrant", archivoId);
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error al eliminar vectores de Qdrant para archivo {}: {}", archivoId, e.getMessage());
            Thread.currentThread().interrupt();
        }
    }

    public IngestaResultado ingestarArchivo(MultipartFile archivo) {
        return ingestarArchivo(archivo, null);
    }

    /**
     * @param progresoId identificador devuelto por ProgresoIngestaService, o null si nadie
     *                   esta mirando el progreso (llamadas internas y pruebas).
     */
    public IngestaResultado ingestarArchivo(MultipartFile archivo, String progresoId) {
        String nombreArchivo = archivo.getOriginalFilename();
        log.info("=== INICIO INGESTA: {} ===", nombreArchivo);

        try {
            // ETAPA 1 — Guardar en MongoDB (raw bytes) y obtener ID
            progresoIngestaService.actualizar(progresoId, ProgresoIngestaService.Fase.SUBIENDO, null);
            String archivoId = archivoService.guardarArchivoYRetornarId(archivo);
            log.info("[ETAPA 1] Guardado en Mongo: ID={}", archivoId);

            // ETAPA 2 — Extraer texto con Tika
            progresoIngestaService.actualizar(progresoId, ProgresoIngestaService.Fase.LEYENDO, null);
            String textoCompleto = tikaExtractorService.extractText(archivo);
            log.info("[ETAPA 2] Texto extraído: {} caracteres", textoCompleto.length());

            // ETAPA 2.1 — Fallo RUIDOSO si no se extrajo texto utilizable.
            //
            // Este es el caso del PDF ESCANEADO: cada página es una imagen, no hay capa de
            // texto, y Tika devuelve una cadena vacía. Sin esta comprobación el canal seguía
            // adelante hasta el final y devolvía `exitoso = true` con CERO fragmentos: el
            // docente veía "material listo", se creaba el Material en la base, y las preguntas
            // se generaban después sin ningún contexto. Un fallo que se presenta como éxito es
            // peor que una excepción, porque nadie lo investiga.
            //
            // El umbral es deliberadamente bajo: no se trata de juzgar si el documento es
            // bueno, sino de distinguir "hay texto" de "no hay nada".
            if (textoCompleto.strip().length() < MINIMO_TEXTO_UTIL) {
                String motivo = "No se pudo extraer texto del documento (solo "
                        + textoCompleto.strip().length() + " caracteres). "
                        + "Si es un PDF escaneado o una foto de páginas, no tiene texto "
                        + "seleccionable y este sistema no puede leerlo. Suba una versión con "
                        + "texto (por ejemplo, exportada desde Word) o pásela antes por un "
                        + "reconocedor de texto.";
                log.warn("[ETAPA 2.1] {}", motivo);
                return new IngestaResultado(archivoId, nombreArchivo, 0, false, motivo);
            }

            // ETAPA 2.2 — Segmentar en secciones lógicas
            progresoIngestaService.actualizar(progresoId, ProgresoIngestaService.Fase.SEGMENTANDO,
                    String.format("%,d caracteres leídos", textoCompleto.length()));
            var segmentacion = segmentadorDocumentoService.segmentar(textoCompleto);
            log.info("[ETAPA 2.2] {} secciones detectadas por {}",
                    segmentacion.secciones().size(), segmentacion.metodo());

            // ETAPA 2.5 — Subtemas POR SECCIÓN, no del documento entero.
            //
            // Antes se mandaba `textoCompleto` en un solo prompt y se pedían "3 a 5 subtemas".
            // Sobre una obra de 300 páginas eso devuelve "el amor, la guerra, el destino":
            // funciona y devuelve basura, que es peor que fallar porque nadie se entera.
            // Por sección, cada capítulo aporta sus propios conceptos.
            progresoIngestaService.actualizar(progresoId, ProgresoIngestaService.Fase.SUBTEMAS,
                    segmentacion.secciones().size() + " secciones detectadas");
            List<String> subtemasDetectados = extraerSubtemas(segmentacion.secciones());
            if (!subtemasDetectados.isEmpty()) {
                archivoService.actualizarSubtemas(archivoId, subtemasDetectados);
                log.info("[ETAPA 2.5] {} subtemas extraídos: {}", subtemasDetectados.size(), subtemasDetectados);
            } else {
                log.warn("[ETAPA 2.5] No se pudo extraer ningún subtema");
            }

            // ETAPA 3 — Trocear cada sección por separado, etiquetando su procedencia.
            // Trocear el documento entero haría que un fragmento pudiera cruzar la frontera
            // entre dos capítulos y quedar sin sección atribuible.
            progresoIngestaService.actualizar(progresoId, ProgresoIngestaService.Fase.TROCEANDO, null);
            List<TextSegment> chunks = new java.util.ArrayList<>();
            for (var seccion : segmentacion.secciones()) {
                List<TextSegment> deSeccion =
                        chunkingService.chunkear(seccion.texto(), archivoId, nombreArchivo);
                for (TextSegment ts : deSeccion) {
                    if (seccion.titulo() != null) {
                        ts.metadata().put("seccion", seccion.titulo());
                    }
                    ts.metadata().put("seccionOrden", String.valueOf(seccion.orden()));
                    // Reservado para la recuperación jerárquica (RAPTOR): 0 = texto literal.
                    ts.metadata().put("nivel", "0");
                    chunks.add(ts);
                }
            }
            log.info("[ETAPA 3] Chunks generados: {}", chunks.size());

            // ETAPA 3.5 — Resúmenes jerárquicos (RAPTOR, Sarthi et al., ICLR 2024).
            //
            // Se añaden como vectores más, en la MISMA colección, con un metadato `nivel`.
            // Es la estrategia de "árbol colapsado": todos los niveles se buscan a la vez y
            // el recuperador no necesita ningún cambio. Sin esto, una pregunta como "cómo
            // cambia el personaje a lo largo de la obra" no tiene respuesta posible, porque
            // no está en ningún fragmento: está repartida entre doscientas páginas.
            chunks.addAll(construirResumenes(segmentacion.secciones(), archivoId, nombreArchivo, progresoId));

            // ETAPA 4 — Embeber POR LOTES y almacenar en Qdrant.
            //
            // Antes se embebía de uno en uno con Thread.sleep(200) entre cada llamada. Para
            // una novela de ~278 fragmentos eso son 2-3 minutos bloqueando la petición HTTP,
            // sin barra de progreso y con riesgo alto de que el navegador corte antes.
            int embeddingsGuardados = embeberPorLotes(chunks, progresoId);
            log.info("[ETAPA 4] {} embeddings guardados en Qdrant", embeddingsGuardados);

            // Segunda red de seguridad: aunque hubiera texto, si NINGÚN vector llegó a Qdrant
            // el material es inservible para generar preguntas. Declararlo fallido permite al
            // docente reintentar en vez de descubrirlo cuando un alumno recibe una evaluación
            // sin contexto.
            if (embeddingsGuardados == 0) {
                String motivo = "El documento se leyó pero no se pudo indexar ningún fragmento. "
                        + "Vuelva a intentarlo; si persiste, avise al administrador.";
                log.error("[ETAPA 4] {}", motivo);
                return new IngestaResultado(archivoId, nombreArchivo, 0, false, motivo);
            }

            return new IngestaResultado(archivoId, nombreArchivo, chunks.size(), true, null);

        } catch (Exception e) {
            log.error("Error en pipeline de ingesta: {}", e.getMessage());
            return new IngestaResultado(null, nombreArchivo, 0, false, e.getMessage());
        }
    }

    /**
     * Mínimo de caracteres para considerar que el documento trae texto.
     *
     * Un PDF escaneado devuelve cadena vacía o unas pocas decenas de caracteres de basura
     * (encabezados, numeración). Con 200 se distingue eso de un documento real sin marcar como
     * fallido una ficha de una sola página.
     */
    private static final int MINIMO_TEXTO_UTIL = 200;

    /** Cuántos fragmentos van en cada llamada de embebido. */
    private static final int TAMANO_LOTE = 25;

    /**
     * TOPES PROPORCIONALES AL TAMAÑO DEL DOCUMENTO.
     *
     * La primera versión usaba dos constantes fijas de 12. Para un PDF de clase estaba bien,
     * pero para una obra completa era absurdo: doce temas no describen un libro de cuarenta
     * capítulos, y el docente veía una lista que dejaba fuera la mayor parte de la obra.
     *
     * Ahora se consulta aproximadamente LA MITAD de las secciones, con un suelo y un techo:
     * el suelo evita quedarse corto en documentos pequeños y el techo evita que una obra
     * enorme dispare el coste sin control.
     */
    static final int MIN_SECCIONES_CONSULTADAS = 8;
    static final int MAX_SECCIONES_CONSULTADAS = 40;
    static final int MIN_SUBTEMAS = 12;
    static final int MAX_SUBTEMAS = 60;

    /** Cuántas secciones se consultan para un documento de N secciones. */
    static int seccionesAConsultar(int totalSecciones) {
        if (totalSecciones <= MIN_SECCIONES_CONSULTADAS) return totalSecciones;
        int mitad = (int) Math.ceil(totalSecciones / 2.0);
        return Math.max(MIN_SECCIONES_CONSULTADAS, Math.min(MAX_SECCIONES_CONSULTADAS, mitad));
    }

    /** Cuántos subtemas se conservan para un documento de N secciones. */
    static int subtemasAConservar(int totalSecciones) {
        return Math.max(MIN_SUBTEMAS, Math.min(MAX_SUBTEMAS, totalSecciones * 2));
    }

    /**
     * Pide subtemas sección por sección y los une sin repetir.
     *
     * La deduplicación es por forma canónica (misma que usa el BKT), de modo que "La
     * fotosíntesis" y "Fotosíntesis" no ocupen dos huecos de la lista.
     */
    private List<String> extraerSubtemas(List<SegmentadorDocumentoService.Seccion> secciones) {
        java.util.LinkedHashMap<String, String> porClave = new java.util.LinkedHashMap<>();

        // Muestreo REPARTIDO a lo largo del documento, no las primeras N.
        //
        // Tomar las 12 primeras secciones de una obra de 40 capítulos hacía que los temas
        // salieran solo del primer tercio: la ingesta decía "éxito", los vectores estaban, y
        // la lista de temas estaba incompleta sin que nada lo indicara. Con un paso fijo se
        // recorre la obra entera aunque solo se consulten 12 secciones.
        int aConsultar = seccionesAConsultar(secciones.size());
        int paso = Math.max(1, (int) Math.ceil(secciones.size() / (double) aConsultar));
        List<SegmentadorDocumentoService.Seccion> muestreadas = new java.util.ArrayList<>();
        for (int i = 0; i < secciones.size(); i += paso) {
            muestreadas.add(secciones.get(i));
        }
        if (paso > 1) {
            log.info("[SUBTEMAS] {} secciones: se consulta 1 de cada {} ({} en total), repartidas por toda la obra",
                    secciones.size(), paso, muestreadas.size());
        }

        for (var seccion : muestreadas) {

            // Se envía solo el principio de la sección: basta para identificar de qué trata y
            // evita mandar un capítulo entero en cada llamada.
            String muestra = seccion.texto().length() > 8000
                    ? seccion.texto().substring(0, 8000)
                    : seccion.texto();

            String prompt = "Analiza este fragmento de un documento educativo y extrae de 2 a 4 "
                    + "conceptos o subtemas principales que aborda. Usa el nombre del concepto en "
                    + "singular y sin artículos (ej. \"Fotosíntesis\", no \"La fotosíntesis\"). "
                    + "Responde ÚNICAMENTE con un array JSON de cadenas, sin ningún otro texto. "
                    + (seccion.titulo() != null ? "\n\nTÍTULO DE LA SECCIÓN: " + seccion.titulo() : "")
                    + "\n\nTEXTO:\n" + muestra;

            try {
                String jsonLimpio = cleanJsonString(geminiService.askGemini(prompt).text());
                List<String> deSeccion = objectMapper.readValue(jsonLimpio, new TypeReference<List<String>>() {});
                for (String bruto : deSeccion) {
                    if (bruto == null || bruto.isBlank()) continue;
                    String clave = com.example.tallerintegrador.service.util.NormalizadorConcepto.canonizar(bruto);
                    if (clave.isEmpty()) continue;
                    porClave.putIfAbsent(clave,
                            com.example.tallerintegrador.service.util.NormalizadorConcepto.paraMostrar(bruto));
                }
            } catch (Exception e) {
                // Una sección que falla no debe tumbar la ingesta entera: se pierden sus
                // subtemas y se sigue con las demás.
                log.warn("[SUBTEMAS] Sección {} sin subtemas: {}", seccion.orden(), e.getMessage());
            }
        }

        return porClave.values().stream().limit(subtemasAConservar(secciones.size())).toList();
    }

    /**
     * Construye los vectores de nivel 1 (resumen de sección) y nivel 2 (resumen de la obra).
     *
     * Solo tiene sentido con varias secciones: en un documento corto, el "resumen del
     * documento" y el texto ya recuperable serían lo mismo, y solo añadiría llamadas al
     * modelo y ruido al índice.
     *
     * Nunca lanza: si los resúmenes fallan, se devuelve una lista vacía y el documento queda
     * indexado igual con sus fragmentos literales. Un resumen mejora la recuperación, no es
     * requisito para guardar el material del docente.
     */
    private List<TextSegment> construirResumenes(
            List<SegmentadorDocumentoService.Seccion> secciones, String archivoId,
            String nombreArchivo, String progresoId) {

        List<TextSegment> vectores = new java.util.ArrayList<>();
        if (secciones == null || secciones.size() < 2) return vectores;

        try {
            progresoIngestaService.actualizar(progresoId, ProgresoIngestaService.Fase.RESUMIENDO,
                    "0 de " + secciones.size() + " secciones");
            var resumenesSeccion = resumidorJerarquicoService.resumirSecciones(secciones);

            for (var r : resumenesSeccion) {
                Metadata meta = new Metadata();
                meta.put("archivoId", archivoId);
                meta.put("nombreArchivo", nombreArchivo);
                meta.put("nivel", "1");
                meta.put("seccionOrden", String.valueOf(r.orden()));
                if (r.titulo() != null) meta.put("seccion", r.titulo());
                // El título se antepone al texto embebido: una consulta por el nombre del
                // capítulo debe poder encontrar su resumen, no solo el contenido.
                String texto = (r.titulo() != null ? r.titulo() + ". " : "") + r.resumen();
                vectores.add(TextSegment.from(texto, meta));
            }

            String resumenObra = resumidorJerarquicoService.resumirDocumento(resumenesSeccion);
            if (resumenObra != null && !resumenObra.isBlank()) {
                Metadata meta = new Metadata();
                meta.put("archivoId", archivoId);
                meta.put("nombreArchivo", nombreArchivo);
                meta.put("nivel", "2");
                vectores.add(TextSegment.from(nombreArchivo + ". " + resumenObra, meta));
            }

            log.info("[ETAPA 3.5] {} vectores de resumen ({} de sección + {} de obra)",
                    vectores.size(), resumenesSeccion.size(), resumenObra != null ? 1 : 0);
        } catch (Exception e) {
            log.warn("[ETAPA 3.5] Sin resúmenes jerárquicos: {}", e.getMessage());
        }
        return vectores;
    }

    /**
     * Embebe en lotes con `embedAll`, que hace una sola llamada por lote en vez de una por
     * fragmento. Si un lote falla, se reintenta fragmento a fragmento: es preferible perder
     * unos pocos vectores a perder el documento completo.
     */
    private int embeberPorLotes(List<TextSegment> chunks, String progresoId) {
        int guardados = 0;

        for (int i = 0; i < chunks.size(); i += TAMANO_LOTE) {
            List<TextSegment> lote = chunks.subList(i, Math.min(i + TAMANO_LOTE, chunks.size()));

            // Progreso fino: sin esto la barra se quedaria quieta los minutos que dura
            // vectorizar, que es justo cuando el docente cree que se colgo.
            progresoIngestaService.actualizarParcial(progresoId,
                    ProgresoIngestaService.Fase.VECTORIZANDO, ProgresoIngestaService.Fase.GUARDANDO,
                    guardados, chunks.size(),
                    String.format("%d de %d fragmentos", guardados, chunks.size()));

            try {
                Response<List<Embedding>> respuesta = embeddingModel.embedAll(lote);
                embeddingStore.addAll(respuesta.content(), lote);
                guardados += lote.size();
            } catch (Exception e) {
                log.warn("[EMBEBIDO] Lote {} falló ({}); se reintenta fragmento a fragmento",
                        i / TAMANO_LOTE, e.getMessage());
                for (TextSegment ts : lote) {
                    try {
                        embeddingStore.add(embeddingModel.embed(ts.text()).content(), ts);
                        guardados++;
                    } catch (Exception ex) {
                        log.error("[EMBEBIDO] Fragmento descartado: {}", ex.getMessage());
                    }
                }
            }
        }
        return guardados;
    }

    private String cleanJsonString(String raw) {
        if (raw == null) return "[]";
        raw = raw.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();
        int startIndex = raw.indexOf("[");
        int endIndex   = raw.lastIndexOf("]");
        if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            return raw.substring(startIndex, endIndex + 1);
        }
        return "[]";
    }

    public record IngestaResultado(
            String  archivoId,
            String  nombreArchivo,
            int     totalChunks,
            boolean exitoso,
            String  errorMensaje
    ) {}
}