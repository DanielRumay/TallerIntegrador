package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.RegistroAcceso;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RegistroAccesoRepository extends JpaRepository<RegistroAcceso, Long> {
    long countByUsuarioId(Long usuarioId);
}
