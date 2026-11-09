package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.SesionCharla;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SesionCharlaRepository extends JpaRepository<SesionCharla, String> {

    long countByUsuarioIdAndSemanaIdAndDesafioFalseAndTerminadaIsNotNull(Long usuarioId, Long semanaId);

    List<SesionCharla> findByIniciadaBetween(LocalDateTime desde, LocalDateTime hasta);

    @Transactional
    @Modifying
    @Query("update SesionCharla s set s.respondidas = s.respondidas + 1 where s.rondaId = :id")
    int sumarRespondida(@Param("id") String rondaId);

    @Transactional
    @Modifying
    @Query("update SesionCharla s set s.terminada = :fin where s.rondaId = :id and s.terminada is null")
    int terminar(@Param("id") String rondaId, @Param("fin") LocalDateTime fin);
}
