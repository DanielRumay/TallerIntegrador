package com.example.tallerintegrador.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank(message = "El nombre es obligatorio")
        String name,

        /**
         * Correo O nombre de usuario. Antes exigía formato de correo y, si faltaba la arroba, se
         * le pegaba un dominio (@gmail.com por defecto): un alumno sin correo acababa con uno
         * inventado que no existe. Ahora "ana.perez" se guarda tal cual y con eso se inicia sesión.
         */
        @NotBlank(message = "El correo o usuario es obligatorio")
        @Pattern(regexp = "^[^\\s@]+(@[^\\s@]+\\.[^\\s@]+)?$",
                message = "Escribe un correo válido o un nombre de usuario sin espacios")
        String email,

        @NotBlank(message = "El rol es obligatorio")
        String role,

        @NotBlank(message = "La contraseña es obligatoria")
        @Size(min = 6, message = "La contraseña debe tener al menos 6 caracteres")
        String password
) {}