package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.TurnoTutorSocratico;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TurnoTutorSocraticoRepository extends JpaRepository<TurnoTutorSocratico, Long> {

    List<TurnoTutorSocratico> findByUsuarioIdOrderByFechaAsc(Long usuarioId);

    /** Solo los turnos cerrados: los que quedaron en repregunta no tienen escalón definitivo. */
    List<TurnoTutorSocratico> findByUsuarioIdAndCerradoTrueOrderByFechaAsc(Long usuarioId);
}
