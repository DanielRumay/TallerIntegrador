package com.example.tallerintegrador.service.rag;

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

            if (end < texto.length()) {
                int ultimoPunto = texto.lastIndexOf(". ", end);

                if (ultimoPunto > start + (CHUNK_CHARS / 2)) {
                    end = ultimoPunto + 1;
                }
            }

            String fragmento = texto.substring(start, end).strip();

            if (!fragmento.isBlank() && fragmento.length() > 50) {
                Metadata meta = new Metadata();
                meta.put("archivoId",     archivoId);
                meta.put("nombreArchivo", nombreArchivo);
                meta.put("chunkIndex",    String.valueOf(segmentos.size()));
                segmentos.add(TextSegment.from(fragmento, meta));
            }

            start = end - OVERLAP_CHARS;

            if (start <= end - fragmento.length()) {
                start = end;
            }
        }

        int total = segmentos.size();
        segmentos.forEach(s -> s.metadata().put("totalChunks", String.valueOf(total)));

        log.info("Archivo '{}' → {} chunks generados", nombreArchivo, total);
        return segmentos;
    }
}