package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.Pregunta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PreguntaRepository extends JpaRepository<Pregunta, Long> {
    List<Pregunta> findBySemanaId(Long semanaId);
    List<Pregunta> findBySemanaIdIn(List<Long> semanaIds);
    void deleteBySemanaIdIn(List<Long> semanaIds);
}