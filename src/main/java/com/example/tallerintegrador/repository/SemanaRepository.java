package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.Semana;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface SemanaRepository extends JpaRepository<Semana, Long> {
    long countByCursoId(Long cursoId);
    List<Semana> findByCursoId(Long cursoId);
    java.util.Optional<Semana> findByMongoId(String mongoId);

    @Transactional
    @Modifying
    void deleteByCursoId(Long courseId);
}
