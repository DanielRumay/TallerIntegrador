package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.CalificacionDocente;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CalificacionDocenteRepository extends JpaRepository<CalificacionDocente, Long> {

    List<CalificacionDocente> findByMuestraOrigen(String origen);

    List<CalificacionDocente> findByDocenteId(Long docenteId);

    Optional<CalificacionDocente> findByMuestraIdAndDocenteId(Long muestraId, Long docenteId);

    boolean existsByMuestraIdAndDocenteId(Long muestraId, Long docenteId);
}
