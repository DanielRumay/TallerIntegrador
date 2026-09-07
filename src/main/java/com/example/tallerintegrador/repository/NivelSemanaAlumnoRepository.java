package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.NivelSemanaAlumno;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NivelSemanaAlumnoRepository extends JpaRepository<NivelSemanaAlumno, Long> {

    Optional<NivelSemanaAlumno> findByUsuarioIdAndSemanaId(Long usuarioId, Long semanaId);

    /** Trayectoria del alumno a lo largo de la intervención, en orden. */
    List<NivelSemanaAlumno> findByUsuarioIdOrderByFechaAsc(Long usuarioId);

    /** Todas las ubicaciones de una semana: base para el coeficiente de reproducibilidad. */
    List<NivelSemanaAlumno> findBySemanaId(Long semanaId);
}
