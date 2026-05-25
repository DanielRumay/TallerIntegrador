package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.Matricula;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface MatriculaRepository extends JpaRepository<Matricula, Long> {
    long countByCursoId(Long cursoId);

    @Transactional
    @Modifying
    void deleteByCursoId(Long courseId);

    Optional<Matricula> findByCursoIdAndUsuarioId(Long cursoId, Long usuarioId);

    List<Matricula> findByCursoId(Long cursoId);
}
