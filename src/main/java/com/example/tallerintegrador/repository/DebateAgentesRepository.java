package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.DebateAgentes;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface DebateAgentesRepository extends JpaRepository<DebateAgentes, Long> {

    List<DebateAgentes> findByUsuarioIdOrderByFechaDesc(Long usuarioId);

    /**
     * Deliberaciones de un alumno en UNA semana, la mas reciente primero.
     *
     * Es la fuente de verdad de si ya hizo la evaluacion recomendadora. Hasta ahora eso se
     * decidia leyendo localStorage del navegador: al entrar desde otro dispositivo, o tras
     * limpiar el navegador, la evaluacion volvia a aparecer como pendiente aunque estuviera
     * hecha y guardada en la base de datos.
     */
    List<DebateAgentes> findByUsuarioIdAndIntentoSemanaIdOrderByFechaDesc(Long usuarioId, Long semanaId);

    List<DebateAgentes> findByFechaBetween(LocalDateTime desde, LocalDateTime hasta);
}
