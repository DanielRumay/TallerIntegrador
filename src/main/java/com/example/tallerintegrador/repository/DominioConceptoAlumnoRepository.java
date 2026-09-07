package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.DominioConceptoAlumno;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DominioConceptoAlumnoRepository extends JpaRepository<DominioConceptoAlumno, Long> {

    Optional<DominioConceptoAlumno> findByUsuarioIdAndConceptoAndNivelBloom(
            Long usuarioId, String concepto, String nivelBloom);

    List<DominioConceptoAlumno> findByUsuarioIdOrderByConceptoAsc(Long usuarioId);
}
