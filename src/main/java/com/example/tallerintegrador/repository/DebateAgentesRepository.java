package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.DebateAgentes;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface DebateAgentesRepository extends JpaRepository<DebateAgentes, Long> {

    List<DebateAgentes> findByUsuarioIdOrderByFechaDesc(Long usuarioId);

    List<DebateAgentes> findByFechaBetween(LocalDateTime desde, LocalDateTime hasta);
}
