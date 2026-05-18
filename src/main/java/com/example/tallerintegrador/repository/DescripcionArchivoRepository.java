package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.Semana;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DescripcionArchivoRepository
        extends JpaRepository<Semana, String> {
}