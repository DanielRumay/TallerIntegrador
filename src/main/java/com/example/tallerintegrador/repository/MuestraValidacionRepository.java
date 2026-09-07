package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.MuestraValidacion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MuestraValidacionRepository extends JpaRepository<MuestraValidacion, Long> {

    List<MuestraValidacion> findByOrigen(String origen);

    boolean existsByOrigenAndOrigenId(String origen, Long origenId);

    long countByOrigen(String origen);
}
