package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.Curso;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CursoRepository extends JpaRepository<Curso, Long> {

    /**
     * Cursos que enseña un docente: los suyos como titular Y aquellos en los que es co-docente.
     * Mantiene el nombre de siempre para que ningún llamador tenga que cambiar.
     */
    @Query("SELECT DISTINCT c FROM Curso c LEFT JOIN c.coDocentes d "
            + "WHERE c.profesor.id = :profesorId OR d.id = :profesorId")
    List<Curso> findByProfesorId(@Param("profesorId") Long profesorId);

    // ¡NUEVO! Para el alumno
    @Query("SELECT m.curso FROM Matricula m WHERE m.usuario.id = :alumnoId")
    List<Curso> findCursosByAlumnoId(@Param("alumnoId") Long alumnoId);

    @Query(value = "SELECT c.id AS course_id, c.nombre AS course_name, c.color AS course_color, c.emoji AS course_emoji, " +
            "COALESCE(AVG(i.nota), 0) AS average_grade, COUNT(i.id) AS total_attempts " +
            "FROM curso c " +
            "LEFT JOIN semana s ON s.curso_id = c.id " +
            "LEFT JOIN intento i ON i.semana_id = s.id " +
            "WHERE c.profesor_id = :profesorId " +
            "   OR EXISTS (SELECT 1 FROM curso_profesor cp WHERE cp.curso_id = c.id AND cp.profesor_id = :profesorId) " +
            "GROUP BY c.id, c.nombre, c.color, c.emoji", nativeQuery = true)
    List<java.util.Map<String, Object>> findRendimientoAlumnosPorCurso(@Param("profesorId") Long profesorId);
}