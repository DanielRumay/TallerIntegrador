package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.Respuesta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RespuestaRepository extends JpaRepository<Respuesta, Long> {
    void deleteByPreguntaId(Long preguntaId);
    void deleteByPreguntaIdIn(List<Long> preguntaIds);
}