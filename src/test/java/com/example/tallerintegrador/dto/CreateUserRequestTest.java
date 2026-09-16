package com.example.tallerintegrador.dto;

import com.example.tallerintegrador.DTO.CreateUserRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Las cuentas se identifican con un correo O con un nombre de usuario, sin dominio inventado. */
class CreateUserRequestTest {

    private final Validator validador = Validation.buildDefaultValidatorFactory().getValidator();

    private boolean valido(String identificador) {
        return validador.validate(new CreateUserRequest("Ana Pérez", identificador, "student", "123456")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"aperez", "ana.perez", "alumno_2b", "ana@colegio.edu.pe", "ana.perez@gmail.com"})
    @DisplayName("Acepta nombres de usuario y correos")
    void acepta(String identificador) {
        assertTrue(valido(identificador), identificador);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ana perez", "ana@", "@colegio.edu", "ana@colegio", " "})
    @DisplayName("Rechaza espacios y correos a medias")
    void rechaza(String identificador) {
        assertFalse(valido(identificador), identificador);
    }
}
