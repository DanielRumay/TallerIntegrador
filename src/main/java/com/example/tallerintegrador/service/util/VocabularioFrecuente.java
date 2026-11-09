package com.example.tallerintegrador.service.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detecta palabras poco frecuentes para la edad del alumno. La legibilidad por sílabas no las ve:
 * una palabra corta puede ser desconocida para un niño.
 *
 * Los cortes por ciclo son un punto de partida propio, a calibrar con la revisión docente. Las
 * palabras que aparecen en el material no cuentan: son el vocabulario que el curso enseña.
 * Lista: FrequencyWords (Hermit Dave), CC-BY-SA 4.0. Ver resources/lenguaje/AVISO.txt.
 */
@Slf4j
@Component
public class VocabularioFrecuente {

    private static final Map<String, Integer> CORTE_POR_CICLO =
            Map.of("III", 4000, "IV", 8000, "V", 12000, "VI", 20000, "VII", 30000);
    private static final int LARGO_MINIMO = 5;
    private static final Pattern PALABRA = Pattern.compile("[\\p{L}]+");

    private final Map<String, Integer> rango = new HashMap<>();

    public VocabularioFrecuente() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new ClassPathResource("lenguaje/es_frecuentes.txt").getInputStream(), StandardCharsets.UTF_8))) {
            String linea;
            int i = 0;
            while ((linea = r.readLine()) != null) {
                if (!linea.isBlank()) rango.putIfAbsent(normalizar(linea.trim()), ++i);
            }
        } catch (IOException e) {
            log.warn("[VOCABULARIO] Lista no disponible; no se revisará el vocabulario: {}", e.getMessage());
        }
    }

    /** Palabras del texto fuera del vocabulario esperado para el ciclo; vacío si el ciclo es desconocido. */
    public List<String> rarasPara(String texto, String ciclo, String material) {
        Integer corte = ciclo == null ? null : CORTE_POR_CICLO.get(ciclo);
        if (corte == null || texto == null || rango.isEmpty()) return List.of();
        String enMaterial = material == null ? "" : normalizar(material);
        LinkedHashSet<String> raras = new LinkedHashSet<>();
        Matcher m = PALABRA.matcher(texto);
        while (m.find()) {
            String palabra = m.group();
            boolean inicioDeFrase = m.start() == 0 || texto.substring(0, m.start()).matches("(?s).*[.!?¿¡:]\\s*$");
            if (palabra.length() < LARGO_MINIMO || (Character.isUpperCase(palabra.charAt(0)) && !inicioDeFrase)) continue;
            String clave = normalizar(palabra);
            if (rango.getOrDefault(clave, Integer.MAX_VALUE) > corte && !enMaterial.contains(clave)) raras.add(palabra);
        }
        return new ArrayList<>(raras);
    }

    private static String normalizar(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
