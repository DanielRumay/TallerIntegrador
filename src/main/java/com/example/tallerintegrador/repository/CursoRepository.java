package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.Curso;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CursoRepository extends JpaRepository<Curso, Long> {

    List<Curso> findByProfesorId(Long profesorId);

    // ¡NUEVO! Para el alumno
    @Query("SELECT m.curso FROM Matricula m WHERE m.usuario.id = :alumnoId")
    List<Curso> findCursosByAlumnoId(@Param("alumnoId") Long alumnoId);
}