package com.example.tallerintegrador.service;

import com.example.tallerintegrador.service.RagRetrieverService.ChunkRelevante;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Worker 2: Context Selector Agent
 * Recibe N chunks recuperados por RAG y pide al LLM que seleccione
 * los más relevantes para generar preguntas de un nivel Bloom específico.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextSelectorAgent {

    private final GeminiService  geminiService;
    private final ObjectMapper   mapper = new ObjectMapper();

    private static final int MAX_CHUNKS_PARA_GENERACION = 4; // Los mejores 4

    /**
     * Pide al LLM que seleccione y filtre los chunks más útiles.
     * Devuelve el contexto ya ensamblado como un solo string.
     */
    public String seleccionarContexto(
            List<ChunkRelevante> chunks,
            String nivelBloom,
            String tipoPregunta) {

        if (chunks.isEmpty()) {
            log.warn("[ContextSelector] No hay chunks disponibles");
            return "";
        }

        // Si hay pocos chunks, usarlos todos directamente sin llamar al LLM
        if (chunks.size() <= MAX_CHUNKS_PARA_GENERACION) {
            return ensamblarContexto(chunks);
        }

        // Construir el prompt para que el LLM seleccione
        String listaChunks = IntStream.range(0, chunks.size())
                .mapToObj(i -> "CHUNK %d (score=%.2f):\n%s".formatted(
                        i + 1, chunks.get(i).score(), chunks.get(i).texto()))
                .collect(Collectors.joining("\n\n---\n\n"));

        String prompt = """
            Eres un experto en pedagogía y diseño de evaluaciones educativas.
            Tu tarea es seleccionar los %d fragmentos de texto más útiles para generar preguntas de tipo '%s'
            en el nivel cognitivo de Bloom: '%s'.
            
            Criterios de selección:
            1. Prioriza fragmentos con conceptos concretos, ejemplos, definiciones o relaciones causales.
            2. Descarta fragmentos que sean solo listas de referencias, índices, o información administrativa.
            3. Prefiere fragmentos que se complementen entre sí para dar contexto completo.
            
            FRAGMENTOS DISPONIBLES:
            %s
            
            Responde ÚNICAMENTE con un JSON válido sin markdown:
            {"indices_seleccionados": [1, 3, 5, 7]}
            (Los índices son los números CHUNK que aparecen en la lista, base 1)
            """.formatted(MAX_CHUNKS_PARA_GENERACION, tipoPregunta, nivelBloom, listaChunks);

        try {
            String respuesta = geminiService.askGemini(prompt).text();
            String jsonLimpio = limpiarJson(respuesta);
            int[] indices = mapper.readTree(jsonLimpio)
                    .path("indices_seleccionados")
                    .traverse()
                    .readValueAs(int[].class);

            List<ChunkRelevante> seleccionados = java.util.Arrays.stream(indices)
                    .filter(i -> i >= 1 && i <= chunks.size())
                    .mapToObj(i -> chunks.get(i - 1))
                    .toList();

            log.info("[ContextSelector] Seleccionados {} de {} chunks", seleccionados.size(), chunks.size());
            return ensamblarContexto(seleccionados);

        } catch (Exception e) {
            log.warn("[ContextSelector] Falló la selección LLM, usando top-{}: {}", MAX_CHUNKS_PARA_GENERACION, e.getMessage());
            return ensamblarContexto(chunks.subList(0, MAX_CHUNKS_PARA_GENERACION));
        }
    }

    /** Concatena los chunks en un texto de contexto limpio */
    private String ensamblarContexto(List<ChunkRelevante> chunks) {
        return chunks.stream()
                .map(c -> "[Fuente: %s]\n%s".formatted(c.nombreArchivo(), c.texto()))
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    private String limpiarJson(String raw) {
        if (raw == null) return "{}";
        raw = raw.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();
        int s = raw.indexOf("{"), e = raw.lastIndexOf("}");
        return (s != -1 && e > s) ? raw.substring(s, e + 1) : "{}";
    }
}