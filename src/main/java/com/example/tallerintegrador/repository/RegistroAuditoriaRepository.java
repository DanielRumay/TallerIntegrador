package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.RegistroAuditoria;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Solo escritura y lectura. NO se declara ningun borrado ni actualizacion: ver la nota de
 * RegistroAuditoria sobre por que un registro alterable no vale como evidencia.
 */
@Repository
public interface RegistroAuditoriaRepository extends JpaRepository<RegistroAuditoria, Long> {

    /**
     * Los filtros vacios se representan con cadena vacia, NO con null.
     *
     * POR QUE. Con ":actor is null or lower(...)", PostgreSQL recibe un parametro sin tipo y lo
     * asume bytea, asi que falla con "no existe la funcion lower(bytea)". Comparando contra ''
     * el parametro siempre viaja como texto y no hay nada que inferir.
     */
    @Query("""
            select r from RegistroAuditoria r
            where (:actor = '' or lower(r.actorCorreo) like lower(concat('%', :actor, '%')))
              and (:accion = '' or str(r.accion) = :accion)
            order by r.fecha desc
            """)
    Page<RegistroAuditoria> buscar(@Param("actor") String actor,
                                   @Param("accion") String accion,
                                   Pageable pagina);
}
