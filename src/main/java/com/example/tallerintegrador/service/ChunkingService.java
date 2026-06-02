package com.example.tallerintegrador.service;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class ChunkingService {

    // ~500 tokens en español ≈ 2000 caracteres
    private static final int CHUNK_CHARS   = 2000;
    private static final int OVERLAP_CHARS = 200;

    public List<TextSegment> chunkear(String texto, String archivoId, String nombreArchivo) {

        List<TextSegment> segmentos = new ArrayList<>();
        int start = 0;

        while (start < texto.length()) {

            int end = Math.min(start + CHUNK_CHARS, texto.length());

            // Intentar romper en límite de oración (punto + espacio)
            if (end < texto.length()) {
                int ultimoPunto = texto.lastIndexOf(". ", end);
                if (ultimoPunto > start + CHUNK_CHARS / 2) {
                    end = ultimoPunto + 2; // incluir el punto y el espacio
                }
            }

            String fragmento = texto.substring(start, end).strip();

            if (!fragmento.isBlank()) {
                Metadata meta = new Metadata();
                meta.put("archivoId",     archivoId);
                meta.put("nombreArchivo", nombreArchivo);
                meta.put("chunkIndex",    String.valueOf(segmentos.size()));
                segmentos.add(TextSegment.from(fragmento, meta));
            }

            // Avanzar con overlap para no perder contexto entre chunks
            start = Math.max(start + 1, end - OVERLAP_CHARS);
        }

        // Ahora que sabemos el total, lo agregamos a cada chunk
        int total = segmentos.size();
        segmentos.forEach(s -> s.metadata().put("totalChunks", String.valueOf(total)));

        log.info("Archivo '{}' → {} chunks (~{} chars c/u)", nombreArchivo, total, CHUNK_CHARS);
        return segmentos;
    }
}