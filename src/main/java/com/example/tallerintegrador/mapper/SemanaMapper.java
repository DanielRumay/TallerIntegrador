package com.example.tallerintegrador.mapper;

import com.example.tallerintegrador.DTO.SemanaDTO;
import com.example.tallerintegrador.entidades.postgres.Semana;
import com.example.tallerintegrador.service.util.IdHasher;
import com.example.tallerintegrador.repository.mongo.ArchivoPromptRepository;
import com.example.tallerintegrador.entidades.mongodb.ArchivoPrompt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class SemanaMapper {

    private final IdHasher idHasher;
    private final ArchivoPromptRepository archivoPromptRepo;

    public SemanaMapper(IdHasher idHasher, ArchivoPromptRepository archivoPromptRepo) {
        this.idHasher = idHasher;
        this.archivoPromptRepo = archivoPromptRepo;
    }

    public SemanaDTO toDTO(Semana semana) {
        List<SemanaDTO.MaterialDTO> materialesDTO = null;

        if (semana.getMateriales() != null) {
            materialesDTO = semana.getMateriales().stream()
                    .sorted((m1, m2) -> {
                        if (m1.getFechaCarga() == null || m2.getFechaCarga() == null) return 0;
                        return m2.getFechaCarga().compareTo(m1.getFechaCarga());
                    })
                    .map(mat -> {
                        List<String> subtemas = null;
                        if (mat.getMongoId() != null) {
                            subtemas = archivoPromptRepo.findById(mat.getMongoId())
                                .map(ArchivoPrompt::getSubtemas)
                                .orElse(null);
                        }
                        
                        return SemanaDTO.MaterialDTO.builder()
                            .id(idHasher.encode(mat.getId()))
                            .nombreArchivo(mat.getNombreArchivo())
                            .mongoId(mat.getMongoId())
                            .visible(mat.isVisible())
                            .fechaCarga(mat.getFechaCarga() != null ? mat.getFechaCarga().toString() : null)
                            .subtemas(subtemas)
                            .build();
                    })
                    .collect(Collectors.toList());
        }

        return SemanaDTO.builder()
                .id(idHasher.encode(semana.getId()))
                .numSem(semana.getNumSem())
                .totalPreguntas(semana.getPreguntas() != null ? semana.getPreguntas().size() : 0)
                .materiales(materialesDTO)
                .build();
    }
}