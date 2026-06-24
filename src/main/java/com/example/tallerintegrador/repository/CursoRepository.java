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

    @Query(value = "SELECT c.id AS course_id, c.nombre AS course_name, c.color AS course_color, c.emoji AS course_emoji, " +
            "COALESCE(AVG(i.nota), 0) AS average_grade, COUNT(i.id) AS total_attempts " +
            "FROM curso c " +
            "LEFT JOIN semana s ON s.curso_id = c.id " +
            "LEFT JOIN intento i ON i.semana_id = s.id " +
            "WHERE c.profesor_id = :profesorId " +
            "GROUP BY c.id, c.nombre, c.color, c.emoji", nativeQuery = true)
    List<java.util.Map<String, Object>> findRendimientoAlumnosPorCurso(@Param("profesorId") Long profesorId);
}