package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<Usuario, Long> {
    Optional<Usuario> findByCorreo(String correo);
    List<Usuario> findByRol(com.example.tallerintegrador.entidades.postgres.Rol rol);
    List<Usuario> findByRolAndNombreContainingIgnoreCase(com.example.tallerintegrador.entidades.postgres.Rol rol, String nombre);

    // --- Gestión por colegio ---

    /**
     * ¿Existe el correo en CUALQUIER colegio? Nativa a propósito: el filtro de colegio de
     * Hibernate haría que un correo de otro colegio pareciera libre, y el INSERT fallaría luego
     * contra la restricción única.
     */
    @org.springframework.data.jpa.repository.Query(value = "SELECT COUNT(*) > 0 FROM usuario WHERE correo = :correo", nativeQuery = true)
    boolean existeCorreo(@org.springframework.data.repository.query.Param("correo") String correo);
    List<Usuario> findByColegioId(Long colegioId);

    List<Usuario> findByColegioIdAndRol(Long colegioId, com.example.tallerintegrador.entidades.postgres.Rol rol);

    List<Usuario> findByColegioIdAndRolAndNombreContainingIgnoreCase(Long colegioId,
            com.example.tallerintegrador.entidades.postgres.Rol rol, String nombre);

    long countByColegioIdAndRol(Long colegioId, com.example.tallerintegrador.entidades.postgres.Rol rol);

    /** Administradores principales activos: un colegio nunca debe quedarse sin ninguno. */
    long countByColegioIdAndRolAndAdminPrincipalTrueAndCuentaBloqueadaFalse(Long colegioId,
            com.example.tallerintegrador.entidades.postgres.Rol rol);

    @org.springframework.data.jpa.repository.Query("select u.id from Usuario u where u.colegio.id = :colegioId")
    java.util.Set<Long> idsDelColegio(@org.springframework.data.repository.query.Param("colegioId") Long colegioId);
}