package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.DiagnosticoAcra;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DiagnosticoAcraRepository extends JpaRepository<DiagnosticoAcra, Long> {

    List<DiagnosticoAcra> findByUsuarioIdOrderByFechaDesc(Long usuarioId);

    Optional<DiagnosticoAcra> findFirstByUsuarioIdOrderByFechaDesc(Long usuarioId);

    boolean existsByUsuarioId(Long usuarioId);
}
