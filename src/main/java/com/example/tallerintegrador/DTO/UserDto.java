package com.example.tallerintegrador.DTO;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Builder
@Setter
@Getter
public class UserDto {
    private String id;
    private String email;
    private String role;
    private String name;
    private String token;
    private boolean consentimientoAceptado;
    private boolean requiresPasswordSetup;
}