package com.example.tallerintegrador.DTO;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
public class MaterialDTO {
    private Long id;
    private String nombreArchivo;
    private String mongoId;
    private Boolean visible;
    private LocalDateTime fechaCarga;
}