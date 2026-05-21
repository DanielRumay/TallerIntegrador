package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.Seccion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SeccionRepository extends JpaRepository<Seccion, Long> {
}
