package com.example.tallerintegrador.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Quita el CHECK que Hibernate 6 crea sobre la columna `tipo` de evento_metrica_ia.
 *
 * Con ddl-auto=update esa lista de valores no se regenera. Sin esto, los tipos de evento nuevos
 * de la charla fallarían al insertarse y, como la telemetría atrapa el error para no romper el
 * flujo del alumno, se perderían en silencio. Es idempotente.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MigracionTelemetria implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbcTemplate.execute("ALTER TABLE IF EXISTS evento_metrica_ia DROP CONSTRAINT IF EXISTS evento_metrica_ia_tipo_check");
        } catch (Exception e) {
            log.warn("[TELEMETRIA] No se pudo revisar el CHECK de evento_metrica_ia: {}", e.getMessage());
        }
    }
}
