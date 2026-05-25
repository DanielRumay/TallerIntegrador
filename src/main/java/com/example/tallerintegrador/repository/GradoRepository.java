package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.Grado;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GradoRepository  extends JpaRepository<Grado,Long> {
}
