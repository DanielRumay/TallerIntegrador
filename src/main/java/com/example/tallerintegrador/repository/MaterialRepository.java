package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.Material;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface MaterialRepository extends JpaRepository<Material, Long> {
    void deleteBySemanaId(Long semanaId);

    @Transactional
    @Modifying
    void deleteBySemanaIdIn(List<Long> semanaIds);
}