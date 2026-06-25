package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.SoporteRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SoporteRequestRepository extends JpaRepository<SoporteRequest, Long> {
}
