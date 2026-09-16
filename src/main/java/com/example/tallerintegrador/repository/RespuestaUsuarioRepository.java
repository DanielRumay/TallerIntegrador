package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.RespuestaUsuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface RespuestaUsuarioRepository extends JpaRepository<RespuestaUsuario, Long> {
    void deleteByPreguntaId(Long preguntaId);

    @Transactional
    @Modifying
    void deleteByPreguntaIdIn(List<Long> preguntaIds);

    List<RespuestaUsuario> findByUsuarioIdAndPreguntaSemanaId(Long usuarioId, Long semanaId);

    List<RespuestaUsuario> findByIntentoId(Long intentoId);

    /**
     * Respuestas y aciertos de un alumno POR SEMANA, en UNA consulta agregada.
     *
     * POR QUÉ EXISTE. El mapa de calor por semana cargaba cada RespuestaUsuario como entidad.
     * Su `pregunta` es @ManyToOne (carga inmediata por defecto) y cada Pregunta arrastra a su
     * vez su semana, su curso, el profesor del curso y sus alternativas: una consulta
     * completa POR RESPUESTA. Con 60 respuestas se veían 60 `select ... from pregunta where
     * id=?` seguidos en el log, para calcular al final dos números por semana.
     *
     * Devuelve filas [semanaId, total, correctas]. Excluye respuestas sin intento y las del
     * diagnóstico, igual que hacía el filtro en memoria que reemplaza.
     */
    @Query("SELECT p.semana.id, COUNT(r), SUM(CASE WHEN r.correcta = true THEN 1 ELSE 0 END) "
            + "FROM RespuestaUsuario r JOIN r.pregunta p JOIN r.intento i "
            + "WHERE r.usuario.id = :usuarioId "
            + "AND (i.tipoEvaluacion IS NULL OR i.tipoEvaluacion <> :excluido) "
            + "GROUP BY p.semana.id")
    List<Object[]> resumenPorSemana(@Param("usuarioId") Long usuarioId,
                                    @Param("excluido") com.example.tallerintegrador.entidades.postgres.TipoEvaluacion excluido);

    List<RespuestaUsuario> findByUsuarioIdAndCorrectaFalseOrderByFechaCreacionDesc(Long usuarioId);

    /**
     * Todas las respuestas dadas a un conjunto de preguntas, para el banco del docente.
     *
     * Se pide en bloque y no pregunta por pregunta a proposito: una semana con 60 reactivos
     * generaria 60 consultas separadas, que es el problema N+1 clasico y se nota en cuanto
     * hay un curso entero con historial.
     */
    List<RespuestaUsuario> findByPreguntaIdIn(List<Long> preguntaIds);
}