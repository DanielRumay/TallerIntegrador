package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.Colegio;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ColegioRepository extends JpaRepository<Colegio, Long> {
    Optional<Colegio> findByNombreIgnoreCase(String nombre);
}
