package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.CuracionTema;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CuracionTemaRepository extends JpaRepository<CuracionTema, Long> {

    List<CuracionTema> findByMaterialId(Long materialId);

    Optional<CuracionTema> findByMaterialIdAndTemaCanonico(Long materialId, String temaCanonico);

    List<CuracionTema> findByMaterialIdInAndAceptadoFalse(List<Long> materialIds);

    long countByAceptadoTrue();

    long countByAceptadoFalse();
}
