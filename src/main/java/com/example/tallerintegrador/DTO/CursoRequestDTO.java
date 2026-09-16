package com.example.tallerintegrador.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CursoRequestDTO(
        @NotBlank(message = "El nombre del curso es obligatorio")
        @Size(max = 100, message = "El nombre del curso no puede superar los 100 caracteres")
        String nombre,

        // Opcional: el formulario del docente la presenta como opcional y exigirla hacía que
        // crear un curso sin descripción fallara con un 400 sin explicación.
        String descripcion,

        @NotNull(message = "El ID del profesor es obligatorio")
        Long profesorId,

        @NotNull(message = "El ID del grado es obligatorio")
        Long gradoId,

        @NotNull(message = "El ID de la sección es obligatorio")
        Long seccionId,

        String emoji,
        @jakarta.validation.constraints.Pattern(
                regexp = "^(primary|lime|coral|#[0-9a-fA-F]{6})$",
                message = "El color debe ser primary, lime, coral o un hexadecimal #RRGGBB")
        String color,
        Integer semanas
) {}
