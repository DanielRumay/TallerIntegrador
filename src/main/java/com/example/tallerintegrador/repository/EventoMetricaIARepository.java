package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.EventoMetricaIA;
import com.example.tallerintegrador.entidades.postgres.TipoEventoIA;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface EventoMetricaIARepository extends JpaRepository<EventoMetricaIA, Long> {

    List<EventoMetricaIA> findByTipoAndFechaBetween(TipoEventoIA tipo, LocalDateTime desde, LocalDateTime hasta);

    List<EventoMetricaIA> findByFechaBetween(LocalDateTime desde, LocalDateTime hasta);

    List<EventoMetricaIA> findByTipoAndUsuarioIdAndFechaBetween(
            TipoEventoIA tipo, Long usuarioId, LocalDateTime desde, LocalDateTime hasta);
}
