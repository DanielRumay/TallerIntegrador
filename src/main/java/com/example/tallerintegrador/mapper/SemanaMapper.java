package com.example.tallerintegrador.mapper;

import com.example.tallerintegrador.DTO.SemanaDTO;
import com.example.tallerintegrador.entidades.postgres.Semana;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class SemanaMapper {

    public SemanaDTO toDTO(Semana semana) {
        List<SemanaDTO.MaterialDTO> materialesDTO = null;

        if (semana.getMateriales() != null) {
            materialesDTO = semana.getMateriales().stream()
                    .sorted((m1, m2) -> {
                        if (m1.getFechaCarga() == null || m2.getFechaCarga() == null) return 0;
                        return m2.getFechaCarga().compareTo(m1.getFechaCarga());
                    })
                    .map(mat -> SemanaDTO.MaterialDTO.builder()
                            .id(mat.getId())
                            .nombreArchivo(mat.getNombreArchivo())
                            .mongoId(mat.getMongoId())
                            .visible(mat.isVisible())
                            .fechaCarga(mat.getFechaCarga() != null ? mat.getFechaCarga().toString() : null)
                            .build())
                    .collect(Collectors.toList());
        }

        return SemanaDTO.builder()
                .id(semana.getId())
                .numSem(semana.getNumSem())
                .totalPreguntas(semana.getPreguntas() != null ? semana.getPreguntas().size() : 0)
                .materiales(materialesDTO)
                .build();
    }
}