package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.DominioConceptoAlumno;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DominioConceptoAlumnoRepository extends JpaRepository<DominioConceptoAlumno, Long> {

    Optional<DominioConceptoAlumno> findByUsuarioIdAndConceptoAndNivelBloom(
            Long usuarioId, String concepto, String nivelBloom);

    List<DominioConceptoAlumno> findByUsuarioIdOrderByConceptoAsc(Long usuarioId);

    @org.springframework.data.jpa.repository.Query(
            "select d from DominioConceptoAlumno d where d.usuario.id = :usuario and d.semana.id = :semana")
    List<DominioConceptoAlumno> deSemana(@org.springframework.data.repository.query.Param("usuario") Long usuarioId,
                                         @org.springframework.data.repository.query.Param("semana") Long semanaId);

    /** [semanaId, concepto] del concepto de otra semana del mismo curso más flojo y sin practicar. */
    @org.springframework.data.jpa.repository.Query("""
            select d.semana.id, coalesce(d.conceptoEtiqueta, d.concepto) from DominioConceptoAlumno d
            where d.usuario.id = :usuario and d.semana.id <> :semana and d.fechaActualizacion < :antes
              and d.curso.id = (select s.curso.id from Semana s where s.id = :semana)
            order by d.probabilidadDominio asc, d.fechaActualizacion asc""")
    List<Object[]> paraRepaso(@org.springframework.data.repository.query.Param("usuario") Long usuarioId,
                              @org.springframework.data.repository.query.Param("semana") Long semanaId,
                              @org.springframework.data.repository.query.Param("antes") java.time.LocalDateTime antes,
                              org.springframework.data.domain.Pageable pagina);
}
