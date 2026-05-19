package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.FragmentoPDF;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FragmentoPDFRepository extends JpaRepository<FragmentoPDF, Long> {
    // JpaRepository ya te da gratis los métodos para guardar (save), buscar, etc.
}