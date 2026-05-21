package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.Matricula;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatriculaRepository extends JpaRepository<Matricula, Long> {
    long countByCursoId(Long cursoId);
}
