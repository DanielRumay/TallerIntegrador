package com.example.tallerintegrador.DTO;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class CursoDocenteDTO {
    private String id;
    private String name;
    private String description;
    private String emoji;
    private String color;
    /** null si el curso no tiene portada; si no, úsalo en la URL para cachear por versión. */
    private Long bannerVersion;
    private Long weeks;
    private Long studentCount;
    private int progress;
}