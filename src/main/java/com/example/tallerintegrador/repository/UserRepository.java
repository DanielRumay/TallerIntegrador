package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.tallerintegrador.entidades.postgres.Rol;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<Usuario, Long> {
    Optional<Usuario> findByCorreo(String correo);

    List<Usuario> findByRolAndNombreContainingIgnoreCase(Rol rol, String nombre);
}