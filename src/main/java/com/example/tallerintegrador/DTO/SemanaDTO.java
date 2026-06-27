package com.example.tallerintegrador.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanaDTO {
    private String id;
    private String numSem;
    private int totalPreguntas;
    private List<MaterialDTO> materiales;

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MaterialDTO {
        private String id;
        private String nombreArchivo;
        private String mongoId;
        private Boolean visible;
        private String fechaCarga;
        private List<String> subtemas;
    }
}