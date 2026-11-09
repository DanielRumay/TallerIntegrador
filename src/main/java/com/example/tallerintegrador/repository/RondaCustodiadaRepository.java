package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.RondaCustodiada;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RondaCustodiadaRepository extends JpaRepository<RondaCustodiada, String> {

    /** Serializa las escrituras de una ronda: dos envíos a la vez no pueden calificar dos veces. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RondaCustodiada r where r.id = :id")
    Optional<RondaCustodiada> bloquear(@Param("id") String id);

    long countByUsuarioIdAndSemanaId(Long usuarioId, Long semanaId);

    @Modifying
    @Query("delete from RondaCustodiada r where r.creada < :limite")
    int borrarAnterioresA(@Param("limite") LocalDateTime limite);
}
