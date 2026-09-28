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

    /**
     * Respuestas de un alumno en una semana, con su pregunta ya cargada.
     *
     * El `join fetch` no es decorativo: sin el, cada RespuestaUsuario dispara una consulta
     * aparte para su pregunta. Ver la nota de findByIntentoId.
     */
    @Query("""
            select ru from RespuestaUsuario ru
            join fetch ru.pregunta p
            where ru.usuario.id = :usuarioId and p.semana.id = :semanaId
            """)
    List<RespuestaUsuario> findByUsuarioIdAndPreguntaSemanaId(@Param("usuarioId") Long usuarioId,
                                                              @Param("semanaId") Long semanaId);

    /**
     * Respuestas de un intento, con su pregunta ya cargada.
     *
     * POR QUE EL JOIN FETCH. `RespuestaUsuario.pregunta` es @ManyToOne sin fetch declarado, o
     * sea carga inmediata. Al recorrer el historial, cada respuesta lanzaba su propia consulta
     * de pregunta, y cada pregunta arrastraba su semana, el curso, el profesor del curso y el
     * grado y la seccion de ambos: seis JOIN para datos que ahi nadie usa. Un alumno con varios
     * intentos generaba cientos de consultas por pantalla, y eso es lo que pone en riesgo el
     * RNF-19 con un aula entera conectada.
     *
     * Es LEFT join porque `pregunta_id` es anulable: con un join normal, una respuesta sin
     * pregunta desapareceria del historial en silencio.
     */
    @Query("""
            select ru from RespuestaUsuario ru
            left join fetch ru.pregunta
            where ru.intento.id = :intentoId
            """)
    List<RespuestaUsuario> findByIntentoId(@Param("intentoId") Long intentoId);

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