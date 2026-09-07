package com.example.tallerintegrador.agents;

import com.example.tallerintegrador.service.ia.GeminiService;
import com.example.tallerintegrador.service.util.JsonParsingUtils;
import com.example.tallerintegrador.service.rag.RagRetrieverService.ChunkRelevante;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


@Slf4j
@Service
@RequiredArgsConstructor
public class ContextSelectorAgent {

    private final GeminiService geminiService;
    private final ObjectMapper   mapper = new ObjectMapper();

    private static final int MAX_CHUNKS_PARA_GENERACION = 4; // Los mejores 4

    public String seleccionarContexto(
            List<ChunkRelevante> chunks,
            String nivelBloom,
            String tipoPregunta) {

        if (chunks.isEmpty()) {
            log.warn("[ContextSelector] No hay chunks disponibles");
            return "";
        }

        if (chunks.size() <= MAX_CHUNKS_PARA_GENERACION) {
            return ensamblarContexto(chunks);
        }

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
            String jsonLimpio = JsonParsingUtils.cleanJsonString(respuesta);
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(jsonLimpio);
            com.fasterxml.jackson.databind.JsonNode indicesNode = root.path("indices_seleccionados");
            java.util.List<Integer> list = new java.util.ArrayList<>();
            if (indicesNode.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode n : indicesNode) {
                    list.add(n.asInt());
                }
            }
            int[] indices = list.stream().mapToInt(Integer::intValue).toArray();

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
}