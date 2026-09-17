package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.RegistroAcceso;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RegistroAccesoRepository extends JpaRepository<RegistroAcceso, Long> {
    long countByUsuarioId(Long usuarioId);

    /** Filas [dia, alumnosDistintos, accesos] de alumnos desde una fecha, un registro por día con actividad. */
    @Query(value = "SELECT CAST(r.fecha AS date) AS dia, COUNT(DISTINCT r.usuario_id) AS alumnos, COUNT(*) AS accesos "
            + "FROM registro_acceso r "
            + "WHERE r.rol = 'STUDENT' AND r.fecha >= :desde "
            + "GROUP BY CAST(r.fecha AS date) ORDER BY dia", nativeQuery = true)
    List<Object[]> accesosPorDia(@Param("desde") LocalDateTime desde);

    /**
     * Filas [nombre, correo, diasDeUso, accesos, ultimoAcceso] de TODOS los alumnos, también los
     * que no entraron nunca (LEFT JOIN): son justo los que más interesa detectar. Los días y
     * accesos cuentan solo dentro del periodo; el último acceso es el de siempre.
     */
    @Query(value = "SELECT u.nombre, u.correo, "
            + "COUNT(DISTINCT CAST(r.fecha AS date)) AS dias, COUNT(r.id) AS accesos, "
            + "(SELECT MAX(r2.fecha) FROM registro_acceso r2 WHERE r2.usuario_id = u.id) AS ultimo "
            + "FROM usuario u "
            + "LEFT JOIN registro_acceso r ON r.usuario_id = u.id AND r.fecha >= :desde "
            + "WHERE u.rol = 'STUDENT' "
            + "GROUP BY u.id, u.nombre, u.correo "
            + "ORDER BY dias ASC, u.nombre ASC", nativeQuery = true)
    List<Object[]> usoPorAlumno(@Param("desde") LocalDateTime desde);
}
