package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.Intento;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface IntentoRepository extends JpaRepository<Intento, Long> {
    List<Intento> findByUsuarioIdOrderByFechaDesc(Long usuarioId);
    List<Intento> findBySemanaIdOrderByFechaDesc(Long semanaId);
}
