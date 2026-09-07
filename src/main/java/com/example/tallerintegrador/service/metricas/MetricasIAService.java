package com.example.tallerintegrador.service.metricas;
import com.example.tallerintegrador.service.rag.PreguntaDedupService;

import com.example.tallerintegrador.entidades.postgres.DebateAgentes;
import com.example.tallerintegrador.entidades.postgres.EventoMetricaIA;
import com.example.tallerintegrador.entidades.postgres.TipoEventoIA;
import com.example.tallerintegrador.repository.DebateAgentesRepository;
import com.example.tallerintegrador.repository.EventoMetricaIARepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lectura y agregación de la telemetría del pipeline de IA.
 *
 * Cada bloque devuelve, además de los números, el campo "interpretacion": qué mide
 * exactamente el indicador y qué NO permite concluir. Es deliberado: un panel de métricas
 * sin esa advertencia es una invitación a sobreinterpretar la conformidad autodeclarada
 * de un modelo como si fuera validez pedagógica verificada.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricasIAService {

    private final EventoMetricaIARepository eventoRepository;
    private final DebateAgentesRepository debateRepository;

    /** Coste por millón de tokens (USD) para estimar el gasto operativo. Ajustable por configuración. */
    private static final double USD_POR_MILLON_INPUT  = 0.10;
    private static final double USD_POR_MILLON_OUTPUT = 0.40;

    // =========================================================================
    // Panel consolidado
    // =========================================================================

    @Transactional(readOnly = true)
    public Map<String, Object> resumen(LocalDateTime desde, LocalDateTime hasta) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ventana", Map.of("desde", desde.toString(), "hasta", hasta.toString()));
        out.put("generacion_bloom", conformidadBloom(desde, hasta));
        out.put("deduplicacion",    deduplicacion(desde, hasta));
        out.put("rag",              rag(desde, hasta));
        out.put("imagenes",         imagenes(desde, hasta));
        out.put("calidad_textual",  calidadTextual(desde, hasta));
        out.put("guardia_enunciado", guardiaEnunciado(desde, hasta));
        out.put("juez",             juez(desde, hasta));
        out.put("comite",           comite(desde, hasta));
        return out;
    }

    // =========================================================================
    // OE1 — Conformidad con la Taxonomía de Bloom
    // =========================================================================

    @Transactional(readOnly = true)
    public Map<String, Object> conformidadBloom(LocalDateTime desde, LocalDateTime hasta) {
        List<EventoMetricaIA> eventos = eventoRepository.findByTipoAndFechaBetween(
                TipoEventoIA.GENERACION_PREGUNTA, desde, hasta);

        long total       = eventos.size();
        long conformes   = contar(eventos, "CONFORME");
        long noConformes = contar(eventos, "NO_CONFORME");
        long sinEtiqueta = contar(eventos, "SIN_ETIQUETA_BLOOM");

        Map<String, Long> porNivel = eventos.stream()
                .filter(e -> e.getNivelBloom() != null)
                .collect(Collectors.groupingBy(EventoMetricaIA::getNivelBloom,
                        TreeMap::new, Collectors.counting()));

        long hots = eventos.stream()
                .filter(e -> esHots(e.getNivelBloom()))
                .count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("preguntas_generadas", total);
        out.put("conformes", conformes);
        out.put("no_conformes", noConformes);
        out.put("sin_etiqueta", sinEtiqueta);
        out.put("tasa_conformidad_declarada", porcentaje(conformes, total));
        out.put("tasa_hots", porcentaje(hots, total));
        out.put("distribucion_por_nivel", porNivel);
        out.put("meta_oe1", 90.0);
        out.put("cumple_meta_oe1", total > 0 && porcentaje(conformes, total) >= 90.0);
        out.put("interpretacion",
                "Mide autoconsistencia del generador: si el nivel de Bloom que el modelo declara " +
                "coincide con el solicitado. NO es validez cognitiva del reactivo. Para sustentar el OE1 " +
                "ante un jurado se requiere además el etiquetado independiente de docentes sobre un " +
                "conjunto de referencia y el cálculo del acuerdo interjueces (kappa).");
        return out;
    }

    // =========================================================================
    // Deduplicación semántica
    // =========================================================================

    @Transactional(readOnly = true)
    public Map<String, Object> deduplicacion(LocalDateTime desde, LocalDateTime hasta) {
        List<EventoMetricaIA> eventos = eventoRepository.findByTipoAndFechaBetween(
                TipoEventoIA.DEDUP_CANDIDATA, desde, hasta);

        long total      = eventos.size();
        long aceptadas  = contar(eventos, "ACEPTADA");
        long exactos    = contar(eventos, "DUPLICADO_EXACTO");
        long vectoriales= contar(eventos, "DUPLICADO_VECTORIAL");
        long errores    = contar(eventos, "ERROR_FILTRO");

        OptionalDouble scoreMedio = eventos.stream()
                .filter(e -> "DUPLICADO_VECTORIAL".equals(e.getEtiqueta()) && e.getValor() != null)
                .mapToDouble(EventoMetricaIA::getValor)
                .average();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("candidatas_evaluadas", total);
        out.put("aceptadas", aceptadas);
        out.put("rechazadas_duplicado_exacto", exactos);
        out.put("rechazadas_similitud_vectorial", vectoriales);
        out.put("errores_filtro", errores);
        out.put("tasa_duplicados", porcentaje(exactos + vectoriales, total));
        out.put("aporte_neto_filtro_vectorial", porcentaje(vectoriales, total));
        out.put("score_medio_rechazo", scoreMedio.isPresent() ? redondear(scoreMedio.getAsDouble()) : null);
        out.put("umbral_similitud", PreguntaDedupService.UMBRAL_SIMILITUD);
        out.put("interpretacion",
                "'aporte_neto_filtro_vectorial' es la métrica que sustenta la contribución: son los " +
                "duplicados que la comparación exacta de texto NO habría detectado. Si tiende a cero, " +
                "el coste del embedding no se justifica.");
        return out;
    }

    // =========================================================================
    // RAG — degradación de la recuperación
    // =========================================================================

    @Transactional(readOnly = true)
    public Map<String, Object> rag(LocalDateTime desde, LocalDateTime hasta) {
        List<EventoMetricaIA> eventos = eventoRepository.findByTipoAndFechaBetween(
                TipoEventoIA.RAG_RECUPERACION, desde, hasta);

        long total = eventos.size();
        Map<String, Long> porUmbral = eventos.stream()
                .collect(Collectors.groupingBy(
                        e -> e.getEtiqueta() != null ? e.getEtiqueta() : "DESCONOCIDO",
                        TreeMap::new, Collectors.counting()));

        long degradadas = eventos.stream()
                .filter(e -> e.getValor() != null && e.getValor() < 0.50)
                .count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recuperaciones", total);
        out.put("distribucion_por_umbral", porUmbral);
        out.put("recuperaciones_degradadas", degradadas);
        out.put("tasa_degradacion", porcentaje(degradadas, total));
        out.put("interpretacion",
                "Una recuperación degradada (umbral < 0.50) produce preguntas a partir de contexto de " +
                "baja relevancia. La tasa de degradación es el límite superior honesto de la afirmación " +
                "de pertinencia curricular del sistema.");
        return out;
    }

    // =========================================================================
    // Calidad textual de las preguntas (Fernández-Huerta + TTR)
    // =========================================================================

    @Transactional(readOnly = true)
    public Map<String, Object> calidadTextual(LocalDateTime desde, LocalDateTime hasta) {
        List<EventoMetricaIA> eventos = eventoRepository.findByTipoAndFechaBetween(
                        TipoEventoIA.GENERACION_PREGUNTA, desde, hasta)
                .stream()
                .filter(e -> "CALIDAD_TEXTUAL".equals(e.getEtiqueta()))
                .toList();

        long total = eventos.size();
        List<Double> legibilidades = eventos.stream()
                .map(EventoMetricaIA::getValor).filter(Objects::nonNull).sorted().toList();
        List<Double> ttrs = eventos.stream()
                .map(e -> extraerTtr(e.getDetalle())).filter(Objects::nonNull).sorted().toList();

        long legibilidadBaja = legibilidades.stream().filter(l -> l < 30).count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("preguntas_medidas", total);
        out.put("legibilidad_media", promedio(legibilidades));
        out.put("legibilidad_minima", legibilidades.isEmpty() ? null : legibilidades.get(0));
        out.put("preguntas_legibilidad_baja", legibilidadBaja);
        out.put("tasa_legibilidad_baja", porcentaje(legibilidadBaja, total));
        out.put("ttr_medio", promedio(ttrs));
        out.put("interpretacion",
                "Índice de Fernández-Huerta: 90-100 muy fácil, 60-70 normal, 0-30 muy difícil/confuso. " +
                "'preguntas_legibilidad_baja' cuenta reactivos con índice < 30 — candidatos a revisión " +
                "por redacción, no por contenido. El TTR (0-1) mide riqueza léxica; valores extremos en " +
                "cualquier dirección (muy repetitivo o inusualmente disperso) también ameritan revisión. " +
                "Ninguna de las dos sustituye el juicio pedagógico: son una señal barata y automática, " +
                "no una validación de que el reactivo mida lo que dice medir.");
        return out;
    }

    private Double extraerTtr(String detalle) {
        if (detalle == null || !detalle.startsWith("ttr=")) return null;
        try {
            return Double.parseDouble(detalle.substring(4));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double promedio(List<Double> valores) {
        return valores.isEmpty() ? null : redondear(valores.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    // =========================================================================
    // Guardia de enunciado — referencias estructurales rechazadas
    // =========================================================================

    @Transactional(readOnly = true)
    public Map<String, Object> guardiaEnunciado(LocalDateTime desde, LocalDateTime hasta) {
        List<EventoMetricaIA> eventos = eventoRepository.findByTipoAndFechaBetween(
                TipoEventoIA.GUARDIA_ENUNCIADO, desde, hasta);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enunciados_rechazados", eventos.size());
        out.put("ejemplos_recientes", eventos.stream()
                .sorted(Comparator.comparing(EventoMetricaIA::getFecha).reversed())
                .limit(10)
                .map(EventoMetricaIA::getDetalle)
                .toList());
        out.put("interpretacion",
                "Cuenta cuántos reactivos el modelo intentó entregar con una referencia a la " +
                "estructura del documento fuente ('según el punto 3', 'en la página 2') pese a que " +
                "el prompt lo prohíbe explícitamente, y que EnunciadoGuard interceptó antes de que " +
                "llegaran al alumno. Un valor sostenidamente alto indica que conviene reforzar el " +
                "prompt o acortar los fragmentos de contexto RAG, que es donde se originan los " +
                "'puntos' y 'secciones' numerados que el modelo termina citando.");
        return out;
    }

    // =========================================================================
    // Validación de imágenes pedagógicas
    // =========================================================================

    @Transactional(readOnly = true)
    public Map<String, Object> imagenes(LocalDateTime desde, LocalDateTime hasta) {
        List<EventoMetricaIA> eventos = eventoRepository.findByTipoAndFechaBetween(
                TipoEventoIA.VALIDACION_IMAGEN, desde, hasta);

        long total    = eventos.size();
        long validas  = contar(eventos, "VALIDA");
        long invalidas= contar(eventos, "INVALIDA");
        long sinImagen= contar(eventos, "SIN_IMAGEN");
        long errores  = contar(eventos, "ERROR_VALIDACION");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("imagenes_generadas", total);
        out.put("validas", validas);
        out.put("invalidas_tras_reintento", invalidas);
        out.put("sin_imagen", sinImagen);
        out.put("errores_validador", errores);
        out.put("tasa_validacion", porcentaje(validas, total));
        out.put("interpretacion",
                "'invalidas_tras_reintento' son imágenes que, incluso después de regenerarse una " +
                "vez con el motivo del rechazo incorporado al prompt, seguían sin corresponder al " +
                "enunciado que las referencia. Se entregan igual (fallar cerrado dejaría al alumno " +
                "sin pregunta), pero quedan marcadas con 'imagen_validada: false' en la respuesta " +
                "de la API para que el docente sepa cuáles revisar. Es la métrica que sustenta o " +
                "desmiente la afirmación de que las imágenes generadas son pedagógicamente " +
                "significativas.");
        return out;
    }

    // =========================================================================
    // Juez de IA — latencia, coste y robustez del parseo
    // =========================================================================

    @Transactional(readOnly = true)
    public Map<String, Object> juez(LocalDateTime desde, LocalDateTime hasta) {
        List<EventoMetricaIA> eventos = eventoRepository.findByTipoAndFechaBetween(
                TipoEventoIA.JUEZ_EVALUACION, desde, hasta);

        long total  = eventos.size();
        long fallos = contar(eventos, "FALLO_LLAMADA");

        List<Long> latencias = eventos.stream()
                .map(EventoMetricaIA::getLatenciaMs)
                .filter(Objects::nonNull)
                .sorted()
                .toList();

        long inputTokens  = sumar(eventos, EventoMetricaIA::getInputTokens);
        long outputTokens = sumar(eventos, EventoMetricaIA::getOutputTokens);

        double costoUsd = (inputTokens  / 1_000_000.0) * USD_POR_MILLON_INPUT
                        + (outputTokens / 1_000_000.0) * USD_POR_MILLON_OUTPUT;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("evaluaciones", total);
        out.put("latencia_media_ms", latencias.isEmpty() ? null
                : redondear(latencias.stream().mapToLong(Long::longValue).average().orElse(0)));
        out.put("latencia_p50_ms", percentil(latencias, 50));
        out.put("latencia_p95_ms", percentil(latencias, 95));
        out.put("input_tokens", inputTokens);
        out.put("output_tokens", outputTokens);
        out.put("costo_estimado_usd", redondear(costoUsd));
        out.put("costo_por_evaluacion_usd", total > 0 ? redondear(costoUsd / total) : null);
        out.put("fallos_de_llamada", fallos);
        out.put("tasa_fallo_llamada", porcentaje(fallos, total));
        out.put("interpretacion",
                "Desde la migración a salida estructurada con esquema (Capability." +
                "RESPONSE_FORMAT_JSON_SCHEMA), Gemini cumple el formato JSON a nivel de API: ya no " +
                "existe el estado intermedio de 'rescate por regex'. 'tasa_fallo_llamada' es ahora la " +
                "única forma de fallo posible: la llamada no se completó (red, cuota, timeout).");
        return out;
    }

    // =========================================================================
    // Comité de agentes — desacuerdo y veto
    // =========================================================================

    @Transactional(readOnly = true)
    public Map<String, Object> comite(LocalDateTime desde, LocalDateTime hasta) {
        List<DebateAgentes> debates = debateRepository.findByFechaBetween(desde, hasta);

        long total   = debates.size();
        long vetados = debates.stream().filter(DebateAgentes::isVetoAplicado).count();
        long fallback= debates.stream().filter(DebateAgentes::isUsoFallback).count();
        long cambios = debates.stream()
                .filter(d -> d.getNivelAnterior() != null && d.getNivelAplicado() != null)
                .filter(d -> d.getNivelAnterior() != d.getNivelAplicado())
                .count();

        Map<String, Long> transiciones = debates.stream()
                .filter(d -> d.getNivelAnterior() != null && d.getNivelAplicado() != null)
                .collect(Collectors.groupingBy(
                        d -> d.getNivelAnterior().name() + " -> " + d.getNivelAplicado().name(),
                        TreeMap::new, Collectors.counting()));

        List<Long> latencias = debates.stream()
                .map(DebateAgentes::getLatenciaTotalMs)
                .filter(Objects::nonNull)
                .sorted()
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("debates", total);
        out.put("vetos_aplicados", vetados);
        out.put("tasa_veto", porcentaje(vetados, total));
        out.put("cambios_de_nivel", cambios);
        out.put("tasa_cambio_nivel", porcentaje(cambios, total));
        out.put("debates_con_fallback_local", fallback);
        out.put("tasa_fallback", porcentaje(fallback, total));
        out.put("transiciones", transiciones);
        out.put("latencia_media_ms", latencias.isEmpty() ? null
                : redondear(latencias.stream().mapToLong(Long::longValue).average().orElse(0)));
        out.put("latencia_p95_ms", percentil(latencias, 95));
        out.put("interpretacion",
                "La 'tasa_veto' es la frecuencia con la que la política determinista corrigió al comité: " +
                "es la evidencia de que la decisión final no queda en manos del LLM. La 'tasa_fallback' " +
                "indica cuántos debates se resolvieron sin el modelo por fallo de la API.");
        return out;
    }

    // =========================================================================
    // Traza de un alumno
    // =========================================================================

    @Transactional(readOnly = true)
    public List<Map<String, Object>> debatesDeAlumno(Long usuarioId) {
        return debateRepository.findByUsuarioIdOrderByFechaDesc(usuarioId).stream()
                .map(d -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", d.getId());
                    m.put("fecha", d.getFecha());
                    m.put("nivel_anterior",  d.getNivelAnterior());
                    m.put("nivel_propuesto", d.getNivelPropuesto());
                    m.put("nivel_aplicado",  d.getNivelAplicado());
                    m.put("veto_aplicado",   d.isVetoAplicado());
                    m.put("motivo_veto",     d.getMotivoVeto());
                    m.put("uso_fallback",    d.isUsoFallback());
                    m.put("latencia_ms",     d.getLatenciaTotalMs());
                    m.put("conceptos_a_reforzar", d.getConceptosAReforzar());
                    m.put("recomendaciones", d.getRecomendaciones());
                    m.put("transcripcion",   d.getDebateTranscripcion());
                    return m;
                })
                .toList();
    }

    // =========================================================================
    // Exportación para el anexo de la tesis
    // =========================================================================

    @Transactional(readOnly = true)
    public String exportarCsv(LocalDateTime desde, LocalDateTime hasta) {
        StringBuilder sb = new StringBuilder(
                "id,tipo,fecha,usuario_id,etiqueta,nivel_bloom,valor,latencia_ms,input_tokens,output_tokens,detalle\n");
        for (EventoMetricaIA e : eventoRepository.findByFechaBetween(desde, hasta)) {
            sb.append(e.getId()).append(',')
              .append(e.getTipo()).append(',')
              .append(e.getFecha()).append(',')
              .append(nvl(e.getUsuarioId())).append(',')
              .append(nvl(e.getEtiqueta())).append(',')
              .append(nvl(e.getNivelBloom())).append(',')
              .append(nvl(e.getValor())).append(',')
              .append(nvl(e.getLatenciaMs())).append(',')
              .append(nvl(e.getInputTokens())).append(',')
              .append(nvl(e.getOutputTokens())).append(',')
              .append(csv(e.getDetalle()))
              .append('\n');
        }
        return sb.toString();
    }

    // =========================================================================
    // Utilitarios
    // =========================================================================

    private long contar(List<EventoMetricaIA> eventos, String etiqueta) {
        return eventos.stream().filter(e -> etiqueta.equals(e.getEtiqueta())).count();
    }

    private long sumar(List<EventoMetricaIA> eventos, Function<EventoMetricaIA, Integer> campo) {
        return eventos.stream()
                .map(campo)
                .filter(Objects::nonNull)
                .mapToLong(Integer::longValue)
                .sum();
    }

    private boolean esHots(String nivelBloom) {
        if (nivelBloom == null) return false;
        String n = nivelBloom.trim().toLowerCase();
        return n.startsWith("analiz") || n.startsWith("evalu") || n.startsWith("crear");
    }

    private double porcentaje(long parte, long total) {
        return total == 0 ? 0.0 : Math.round((parte * 10000.0 / total)) / 100.0;
    }

    private Double percentil(List<Long> ordenados, int p) {
        if (ordenados.isEmpty()) return null;
        int idx = (int) Math.ceil(p / 100.0 * ordenados.size()) - 1;
        return (double) ordenados.get(Math.max(0, Math.min(idx, ordenados.size() - 1)));
    }

    private double redondear(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private String nvl(Object o) {
        return o == null ? "" : o.toString();
    }

    private String csv(String s) {
        if (s == null) return "";
        return '"' + s.replace("\"", "'").replace('\n', ' ') + '"';
    }
}
