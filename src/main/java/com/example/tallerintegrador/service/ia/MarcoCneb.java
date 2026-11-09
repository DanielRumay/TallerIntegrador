package com.example.tallerintegrador.service.ia;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Lo que el Curriculo Nacional (CNEB) espera de un estudiante en su area y su grado.
 *
 * POR QUE EXISTE. PerfilAudiencia usaba el CNEB solo para el ciclo, es decir, para el lenguaje.
 * Lo que se evaluaba quedaba a criterio del modelo. Este bloque le entrega las competencias del
 * area del curso, con los desempenos del grado exacto del alumno.
 *
 * DE DONDE SALE. cneb/secundaria.json se genero del Programa Curricular de Educacion Secundaria
 * del Minedu; no se redacto a mano, porque una norma parafraseada deja de ser la norma. Solo
 * trae secundaria y las competencias que se pueden evaluar con reactivos escritos.
 *
 * TRES EJES SEPARADOS. El marco dice QUE se espera del grado, Bloom cuanta exigencia cognitiva
 * tiene el reactivo y la audiencia en que lenguaje se escribe. El contenido sale solo del
 * material de la semana: el marco no autoriza a preguntar lo que el docente no dio.
 *
 * Nunca falla: sin datos, sin grado o con un curso que no se reconoce devuelve texto vacio.
 */
@Slf4j
@Component
public class MarcoCneb {

    /** Tope por competencia, para que un area con varias no desplace al material en el prompt. */
    static final int MAX_DESEMPENOS = 6;
    /** Con mas competencias que estas, el estandar se omite: los desempenos del grado son mas concretos. */
    static final int MAX_COMPETENCIAS_CON_ESTANDAR = 2;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Ciclo(String estandar, Map<String, List<String>> desempenos) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Competencia(String area, Map<String, Ciclo> ciclos) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Programa(String fuente, Map<String, Competencia> competencias) {}

    private final Map<String, Competencia> competencias;

    public MarcoCneb() {
        Map<String, Competencia> cargadas = Map.of();
        try (InputStream in = new ClassPathResource("cneb/secundaria.json").getInputStream()) {
            cargadas = new ObjectMapper().readValue(in, Programa.class).competencias();
        } catch (Exception e) {
            log.warn("[CNEB] No se pudo cargar el programa curricular: {}", e.getMessage());
        }
        this.competencias = new LinkedHashMap<>(cargadas);
    }

    /** Area curricular segun el nombre del curso, o null si no se reconoce. */
    static String areaDe(String nombreCurso) {
        if (nombreCurso == null) return null;
        String n = Normalizer.normalize(nombreCurso, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        // Educacion Fisica va primero: contiene "fisica" y no se evalua con reactivos escritos.
        if (n.contains("educacion fisica")) return null;
        if (contieneAlguno(n, "matematic", "algebra", "aritmetic", "geometr", "trigonometr", "estadistic")) return "Matemática";
        if (contieneAlguno(n, "ingles", "english")) return "Inglés";
        if (contieneAlguno(n, "comunicacion", "lenguaje", "literatura", "lectura", "razonamiento verbal", "redaccion")) return "Comunicación";
        if (contieneAlguno(n, "sociales", "historia", "geografia", "economia")) return "Ciencias Sociales";
        if (contieneAlguno(n, "ciencia", "biologia", "fisica", "quimica", "cta")) return "Ciencia y Tecnología";
        if (contieneAlguno(n, "personal", "ciudadan", "civica", "dpcc")) return "Desarrollo Personal, Ciudadanía y Cívica";
        return null;
    }

    private static boolean contieneAlguno(String texto, String... partes) {
        for (String p : partes) if (texto.contains(p)) return true;
        return false;
    }

    /** Bloque para el prompt, o vacio si no hay marco aplicable a este curso y grado. */
    public String instruccion(String nombreCurso, PerfilAudiencia audiencia) {
        if (audiencia == null || !audiencia.esConocido() || !audiencia.secundaria()) return "";
        String area = areaDe(nombreCurso);
        if (area == null) return "";

        String clave = String.valueOf(audiencia.grado());
        Map<String, Competencia> delArea = competencias.entrySet().stream()
                .filter(e -> area.equals(e.getValue().area()))
                .filter(e -> e.getValue().ciclos().containsKey(audiencia.ciclo()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
        if (delArea.isEmpty()) return "";

        boolean conEstandar = delArea.size() <= MAX_COMPETENCIAS_CON_ESTANDAR;
        StringBuilder sb = new StringBuilder("MARCO CURRICULAR (Currículo Nacional del Perú): área de ")
                .append(area).append(", ciclo ").append(audiencia.ciclo()).append(", ")
                .append(audiencia.grado()).append(".° de secundaria.\n")
                .append("Cada reactivo debe contribuir a una de estas competencias, con la exigencia propia de este grado. "
                        + "Escribe su nombre exacto en 'competencia_cneb'.\n");
        delArea.forEach((nombre, competencia) -> {
            Ciclo ciclo = competencia.ciclos().get(audiencia.ciclo());
            sb.append("- COMPETENCIA: ").append(nombre).append('\n');
            if (conEstandar) sb.append("  Estándar del ciclo: ").append(ciclo.estandar()).append('\n');
            List<String> desempenos = ciclo.desempenos().getOrDefault(clave, List.of());
            desempenos.stream().limit(MAX_DESEMPENOS).forEach(d -> sb.append("  * ").append(d).append('\n'));
        });
        return sb.append("El marco orienta qué se espera del grado. El contenido sale solo del TEXTO entregado "
                + "y la exigencia cognitiva sigue siendo el nivel de Bloom indicado.").toString();
    }
}
