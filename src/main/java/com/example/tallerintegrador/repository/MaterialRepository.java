package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.Material;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MaterialRepository extends JpaRepository<Material, Long> {
}