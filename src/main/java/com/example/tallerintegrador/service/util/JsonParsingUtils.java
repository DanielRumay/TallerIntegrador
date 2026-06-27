package com.example.tallerintegrador.service.util;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JsonParsingUtils {

    public static String cleanJsonString(String raw) {
        if (raw == null) return "{}";

        // Limpiar bloques de código markdown
        raw = raw.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();

        int firstBrace = raw.indexOf("{");
        int firstBracket = raw.indexOf("[");

        // Determinar qué tipo de estructura JSON empieza primero
        if (firstBrace != -1 && (firstBracket == -1 || firstBrace < firstBracket)) {
            int endIndex = raw.lastIndexOf("}");
            if (endIndex > firstBrace) {
                return raw.substring(firstBrace, endIndex + 1);
            }
        } else if (firstBracket != -1) {
            int endIndex = raw.lastIndexOf("]");
            if (endIndex > firstBracket) {
                return raw.substring(firstBracket, endIndex + 1);
            }
        }

        // Fallback al comportamiento original por si acaso
        int startIndex = raw.indexOf("{");
        int endIndex   = raw.lastIndexOf("}");

        if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            return raw.substring(startIndex, endIndex + 1);
        } else {
            log.warn("No se encontraron delimitadores de JSON válidos en la respuesta.");
            return "{}";
        }
    }
}
