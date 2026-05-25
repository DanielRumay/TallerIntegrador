package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.Respuesta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface RespuestaRepository extends JpaRepository<Respuesta, Long> {
    void deleteByPreguntaId(Long preguntaId);

    @Transactional
    @Modifying
    void deleteByPreguntaIdIn(List<Long> preguntaIds);
}