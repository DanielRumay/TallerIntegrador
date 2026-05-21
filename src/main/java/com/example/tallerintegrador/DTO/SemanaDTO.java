package com.example.tallerintegrador.DTO;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Builder
public class SemanaDTO {
    private Long id;
    private String numSem;
    private int totalPreguntas;

    private List<MaterialDTO> materiales;

    @Getter @Setter @Builder
    public static class MaterialDTO {
        private Long id;
        private String nombreArchivo;
        private String mongoId;
    }
}