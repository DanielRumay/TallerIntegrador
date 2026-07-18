package com.example.tallerintegrador.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CursoRequestDTO(
        @NotBlank(message = "El nombre del curso es obligatorio")
        @Size(max = 100, message = "El nombre del curso no puede superar los 100 caracteres")
        String nombre,

        @NotBlank(message = "La descripción del curso es obligatoria")
        String descripcion,

        @NotNull(message = "El ID del profesor es obligatorio")
        Long profesorId,

        @NotNull(message = "El ID del grado es obligatorio")
        Long gradoId,

        @NotNull(message = "El ID de la sección es obligatorio")
        Long seccionId,

        String emoji,
        String color,
        Integer semanas
) {}
