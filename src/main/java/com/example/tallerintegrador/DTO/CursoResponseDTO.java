package com.example.tallerintegrador.DTO;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CursoResponseDTO {
    private String id;
    private String name;
    private String description;
    private String emoji;
    private String color;
    private String nombreProfesor;
}