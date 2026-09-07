package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.RespuestaUsuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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