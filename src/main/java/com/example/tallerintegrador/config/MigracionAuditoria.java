package com.example.tallerintegrador.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Quita el CHECK que Hibernate 6 crea sobre la columna `accion` de registro_auditoria.
 *
 * POR QUE. Al crear la tabla, Hibernate fija la lista de valores del enum en un CHECK; con
 * ddl-auto=update esa lista no se vuelve a generar. Al agregar acciones nuevas (las de cuentas,
 * RFS-07) el INSERT fallaria, y como AuditoriaService atrapa el error para no romper la
 * operacion, la accion quedaria sin registrar en silencio. El enum en Java ya restringe los
 * valores, asi que el CHECK no aporta nada.
 *
 * Es idempotente: si la restriccion no existe, no hace nada.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MigracionAuditoria implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbcTemplate.execute("ALTER TABLE IF EXISTS registro_auditoria "
                    + "DROP CONSTRAINT IF EXISTS registro_auditoria_accion_check");
        } catch (Exception e) {
            log.warn("[AUDITORIA] No se pudo revisar el CHECK de registro_auditoria: {}", e.getMessage());
        }
    }
}
