package com.example.tallerintegrador.seguridad;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Impide reintroducir consultas que devuelven datos de TODOS los colegios.
 *
 * Una sola línea "userRepository.findAll()" en un servicio basta para filtrar usuarios de otro
 * colegio. Esta prueba recorre el código de controladores y servicios y falla si aparece un
 * findAll() sobre usuarios, cursos o intentos sin el comentario "global-ok" que justifique por
 * qué es seguro (por ejemplo, porque se filtra por colegio en la línea siguiente).
 */
class SinConsultasGlobalesTest {

    private static final Pattern GLOBAL = Pattern.compile(
            "\\b(userRepository|userRepo|usuarioRepository|cursoRepository|intentoRepository)\\.findAll\\(\\)");

    @Test
    @DisplayName("Ningún servicio ni controlador lista usuarios, cursos o intentos de todos los colegios")
    void sinFindAllGlobales() throws IOException {
        List<String> infracciones = new ArrayList<>();
        for (String carpeta : List.of("service", "controller")) {
            Path raiz = Path.of("src/main/java/com/example/tallerintegrador", carpeta);
            try (Stream<Path> archivos = Files.walk(raiz)) {
                for (Path archivo : archivos.filter(p -> p.toString().endsWith(".java")).toList()) {
                    List<String> lineas = Files.readAllLines(archivo, StandardCharsets.UTF_8);
                    for (int i = 0; i < lineas.size(); i++) {
                        String linea = lineas.get(i);
                        String codigo = linea.contains("//") ? linea.substring(0, linea.indexOf("//")) : linea;
                        boolean enComentario = linea.strip().startsWith("*") || linea.strip().startsWith("//");
                        if (!enComentario && GLOBAL.matcher(codigo).find() && !linea.contains("global-ok")) {
                            infracciones.add(archivo.getFileName() + ":" + (i + 1) + "  " + linea.strip());
                        }
                    }
                }
            }
        }
        assertThat(infracciones).as("findAll() sin filtro de colegio").isEmpty();
    }
}
