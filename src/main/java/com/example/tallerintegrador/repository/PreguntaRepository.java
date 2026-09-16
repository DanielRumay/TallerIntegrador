package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.Pregunta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface PreguntaRepository extends JpaRepository<Pregunta, Long> {
    List<Pregunta> findBySemanaId(Long semanaId);
    List<Pregunta> findBySemanaIdIn(List<Long> semanaIds);

    /**
     * Cuántas preguntas tiene cada semana, en UNA sola consulta.
     *
     * POR QUÉ EXISTE. La lista de semanas solo necesita el NÚMERO de preguntas, pero se
     * calculaba con `semana.getPreguntas().size()`. Ese `.size()` obliga a Hibernate a
     * materializar cada Pregunta como entidad y, como `Pregunta.respuestas` está mapeado
     * EAGER, a lanzar además una consulta por pregunta para traer sus alternativas. Abrir un
     * curso de 12 semanas con 30 preguntas cada una disparaba ~360 consultas para calcular
     * doce enteros; el log del navegador se llenaba de `select ... from respuesta where
     * pregunta_id=?` idénticos.
     *
     * Contando en SQL no se carga ni una Pregunta, así que el EAGER ni se dispara.
     *
     * Devuelve filas [semanaId, cantidad]; el servicio las pasa a Map.
     */
    @Query("select p.semana.id, count(p) from Pregunta p where p.semana.id in :semanaIds group by p.semana.id")
    List<Object[]> contarPorSemana(@Param("semanaIds") List<Long> semanaIds);

    @Transactional
    @Modifying
    void deleteBySemanaIdIn(List<Long> semanaIds);
}