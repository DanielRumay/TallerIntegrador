package com.example.tallerintegrador.controller;

import com.google.common.util.concurrent.Futures;
import io.qdrant.client.QdrantClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    private JdbcTemplate jdbc;
    private MongoTemplate mongo;
    private QdrantClient qdrant;
    private HealthController controller;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        mongo = mock(MongoTemplate.class);
        qdrant = mock(QdrantClient.class);
        controller = new HealthController(jdbc, mongo, qdrant);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(qdrant.healthCheckAsync()).thenReturn(Futures.immediateFuture(null));
    }

    @Test
    void todoArribaDevuelve200() {
        ResponseEntity<Map<String, Object>> r = controller.healthCheck();
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("status", "OK");
    }

    @Test
    void qdrantCaidoDevuelve503SinDetallesInternos() {
        when(qdrant.healthCheckAsync()).thenReturn(Futures.immediateFailedFuture(new RuntimeException("UNAVAILABLE: io exception")));
        ResponseEntity<Map<String, Object>> r = controller.healthCheck();
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(r.getBody()).containsEntry("status", "DEGRADADO");
        assertThat(r.getBody().get("servicios")).isEqualTo(Map.of("postgres", "OK", "mongodb", "OK", "qdrant", "CAIDO"));
        assertThat(r.getBody().toString()).doesNotContain("io exception");
    }

    @Test
    void postgresCaidoDevuelve503() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenThrow(new RuntimeException("Connection refused"));
        assertThat(controller.healthCheck().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
