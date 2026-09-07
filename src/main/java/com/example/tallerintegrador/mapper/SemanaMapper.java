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
    private final com.example.tallerintegrador.service.academico.CuracionTemasService curacionTemasService;

    public SemanaMapper(IdHasher idHasher, ArchivoPromptRepository archivoPromptRepo,
                        com.example.tallerintegrador.service.academico.CuracionTemasService curacionTemasService) {
        this.idHasher = idHasher;
        this.archivoPromptRepo = archivoPromptRepo;
        this.curacionTemasService = curacionTemasService;
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

                        // Se retiran los temas que el docente descartó por no ser temas
                        // reales (índices, créditos, bibliografía). El filtro compara por
                        // forma canónica, no por texto: si en una reingesta el modelo
                        // devuelve "La fotosíntesis" donde antes se descartó "Fotosíntesis",
                        // sigue descartado y el docente no tiene que repetir el trabajo.
                        //
                        // Los temas SIN revisar se conservan: el criterio por defecto es
                        // mostrar. Ocultar lo no revisado dejaría al alumno sin temas
                        // mientras el docente no entrara a validarlos.
                        if (subtemas != null && !subtemas.isEmpty()) {
                            java.util.Set<String> ocultos =
                                    curacionTemasService.temasOcultosDe(List.of(mat.getId()));
                            if (!ocultos.isEmpty()) {
                                subtemas = subtemas.stream()
                                        .filter(t -> !ocultos.contains(
                                                com.example.tallerintegrador.service.util
                                                        .NormalizadorConcepto.canonizar(t)))
                                        .toList();
                            }
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
                .nombreTema(resolverNombreTema(semana))
                .totalPreguntas(semana.getPreguntas() != null ? semana.getPreguntas().size() : 0)
                .habilitada(semana.isHabilitada())
                .materiales(materialesDTO)
                .build();
    }

    /**
     * Si la semana no tiene un nombreTema persistido (semanas creadas antes de este
     * cambio, o cuya subida de material aún no terminó de derivarlo), se calcula al vuelo
     * a partir de los subtemas del material más reciente. Evita una migración de datos
     * para las semanas ya existentes.
     */
    private String resolverNombreTema(Semana semana) {
        if (semana.getNombreTema() != null && !semana.getNombreTema().isBlank()) {
            return semana.getNombreTema();
        }
        if (semana.getMateriales() == null || semana.getMateriales().isEmpty()) {
            return null;
        }
        return semana.getMateriales().stream()
                .filter(m -> m.getMongoId() != null)
                .sorted((m1, m2) -> {
                    if (m1.getFechaCarga() == null || m2.getFechaCarga() == null) return 0;
                    return m2.getFechaCarga().compareTo(m1.getFechaCarga());
                })
                .map(m -> archivoPromptRepo.findById(m.getMongoId()).map(ArchivoPrompt::getSubtemas).orElse(null))
                .filter(subtemas -> subtemas != null && !subtemas.isEmpty())
                .findFirst()
                .map(subtemas -> String.join(" · ", subtemas.subList(0, Math.min(2, subtemas.size()))))
                .orElse(null);
    }
}