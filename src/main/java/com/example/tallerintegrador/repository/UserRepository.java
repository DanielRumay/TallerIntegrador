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
}