package com.example.tallerintegrador.service.metricas;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Los registros de acceso se guardan en UTC sin zona. El día de uso se cuenta en hora de Perú,
 * así que el inicio de un día en Lima es 5 horas después, en UTC.
 */
class UsoPlataformaZonaTest {

    @Test
    @DisplayName("El día de Lima empieza a las 05:00 UTC")
    void inicioDelDiaEnUtc() {
        assertThat(UsoPlataformaService.inicioDelDiaEnUtc(LocalDate.of(2026, 10, 12)))
                .isEqualTo(LocalDateTime.of(2026, 10, 12, 5, 0));
    }

    @Test
    @DisplayName("Un acceso a las 19:30 de Lima (00:30 UTC del día siguiente) sigue siendo del mismo día")
    void accesoNocturnoNoCambiaDeDia() {
        LocalDateTime accesoUtc = LocalDateTime.of(2026, 10, 13, 0, 30);
        LocalDateTime inicio = UsoPlataformaService.inicioDelDiaEnUtc(LocalDate.of(2026, 10, 12));
        LocalDateTime fin = UsoPlataformaService.inicioDelDiaEnUtc(LocalDate.of(2026, 10, 13));
        assertThat(accesoUtc).isAfterOrEqualTo(inicio).isBefore(fin);
    }
}
