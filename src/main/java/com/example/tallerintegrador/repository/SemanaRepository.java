package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.Semana;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SemanaRepository extends JpaRepository<Semana, Long> {
    long countByCursoId(Long cursoId);
    List<Semana> findByCursoId(Long cursoId);

    void deleteByCursoId(Long courseId);
}
