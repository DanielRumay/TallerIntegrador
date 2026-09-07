package com.example.tallerintegrador.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Configuración explícita de Spring Data para eliminar el warning:
 * "Could not safely identify store assignment for repository..."
 *
 * Estrategia:
 * - JPA (PostgreSQL): escanea el paquete raíz "repository", excluyendo el subpaquete "mongo".
 * - MongoDB: escanea exclusivamente el subpaquete "repository.mongo".
 *
 * El filtro de exclusión evita que Spring Data JPA intente registrar
 * ArchivoPromptRepository (MongoRepository) como repositorio JPA.
 */
@Configuration
@EnableJpaRepositories(
        basePackages = "com.example.tallerintegrador.repository",
        excludeFilters = @ComponentScan.Filter(
                type = org.springframework.context.annotation.FilterType.REGEX,
                pattern = "com\\.example\\.tallerintegrador\\.repository\\.mongo\\..*"
        )
)
@EnableMongoRepositories(
        basePackages = "com.example.tallerintegrador.repository.mongo"
)
public class DataSourceConfig {

    @org.springframework.context.annotation.Bean
    public org.springframework.boot.CommandLineRunner schemaMigrationRunner(javax.sql.DataSource dataSource) {
        return args -> {
            try (var conn = dataSource.getConnection();
                 var stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE semana ADD COLUMN IF NOT EXISTS habilitada BOOLEAN DEFAULT true;");
            } catch (Exception ignored) {}
        };
    }
}
