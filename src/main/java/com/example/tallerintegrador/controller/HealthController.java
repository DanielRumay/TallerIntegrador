package com.example.tallerintegrador.controller;

import io.qdrant.client.QdrantClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Endpoint público que consulta el monitor externo (UptimeRobot) cada pocos minutos.
 *
 * No basta con que la JVM responda: si PostgreSQL, MongoDB o Qdrant están caídos, la plataforma
 * no sirve aunque Spring siga vivo (así pasó con Qdrant 1.12). Por eso comprueba las tres bases
 * y devuelve 503 si alguna falla, que es lo que el monitor interpreta como caída.
 * La respuesta no incluye mensajes de error internos: el endpoint es público.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class HealthController {

    private static final long TIMEOUT_QDRANT_SEGUNDOS = 3;

    private final JdbcTemplate jdbcTemplate;
    private final MongoTemplate mongoTemplate;
    private final QdrantClient qdrantClient;

    @GetMapping("/health-check")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, String> servicios = new LinkedHashMap<>();
        servicios.put("postgres", comprobar("postgres", () -> jdbcTemplate.queryForObject("SELECT 1", Integer.class)));
        servicios.put("mongodb", comprobar("mongodb", () -> mongoTemplate.executeCommand("{ ping: 1 }")));
        servicios.put("qdrant", comprobar("qdrant", () -> qdrantClient.healthCheckAsync().get(TIMEOUT_QDRANT_SEGUNDOS, TimeUnit.SECONDS)));

        boolean todoBien = servicios.values().stream().allMatch("OK"::equals);
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("status", todoBien ? "OK" : "DEGRADADO");
        cuerpo.put("servicios", servicios);
        return ResponseEntity.status(todoBien ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(cuerpo);
    }

    private String comprobar(String nombre, Callable<?> prueba) {
        try {
            prueba.call();
            return "OK";
        } catch (Exception e) {
            log.warn("Health-check: {} no responde: {}", nombre, e.getMessage());
            return "CAIDO";
        }
    }
}
