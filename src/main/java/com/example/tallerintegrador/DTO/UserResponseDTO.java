package com.example.tallerintegrador.DTO;

public record UserResponseDTO(
        Long id,
        String nombre,
        String correo,
        String rol
) {}