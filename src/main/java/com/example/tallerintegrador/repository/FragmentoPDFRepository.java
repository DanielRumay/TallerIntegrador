package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.FragmentoPDF;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FragmentoPDFRepository extends JpaRepository<FragmentoPDF, Long> {
    @Query(value = """
        SELECT * FROM fragmentos_pdf
        ORDER BY embedding <=> CAST(:vector AS vector)
        LIMIT :limite
        """, nativeQuery = true)
    List<FragmentoPDF> buscarPorSimilitud(
            @Param("vector") String vector,
            @Param("limite") int limite
    );
}