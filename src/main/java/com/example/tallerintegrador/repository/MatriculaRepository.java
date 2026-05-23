package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.Matricula;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MatriculaRepository extends JpaRepository<Matricula, Long> {
    long countByCursoId(Long cursoId);

    void deleteByCursoId(Long courseId);

    Optional<Matricula> findByCursoIdAndUsuarioId(Long cursoId, Long usuarioId);

    List<Matricula> findByCursoId(Long cursoId);
}
