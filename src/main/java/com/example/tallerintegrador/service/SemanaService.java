package com.example.tallerintegrador.service;

import com.example.tallerintegrador.DTO.SemanaDTO;
import com.example.tallerintegrador.entidades.postgres.Material;
import com.example.tallerintegrador.entidades.postgres.Semana;
import com.example.tallerintegrador.mapper.SemanaMapper;
import com.example.tallerintegrador.repository.MaterialRepository;
import com.example.tallerintegrador.repository.SemanaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class SemanaService {

    private final SemanaRepository semanaRepository;
    private final MaterialRepository materialRepository;
    private final SemanaMapper semanaMapper;

    // Inyectamos el pipeline completo de RAG en lugar del EvaluacionIAService
    // antiguo
    private final RagIngestionService ragIngestionService;
    private final ArchivoService archivoService;

    public SemanaDTO obtenerSemana(Long semanaId) {
        Semana semana = semanaRepository.findById(semanaId)
                .orElseThrow(() -> new RuntimeException("Semana no encontrada"));
        return semanaMapper.toDTO(semana);
    }

    public SemanaDTO subirArchivos(Long semanaId, List<MultipartFile> archivos) {
        Semana semana = semanaRepository.findById(semanaId)
                .orElseThrow(() -> new RuntimeException("Semana no encontrada"));

        for (MultipartFile archivo : archivos) {
            var resultado = ragIngestionService.ingestarArchivo(archivo);

            if (resultado.exitoso()) {
                // Solo si el RAG fue exitoso se guarda
                Material material = new Material();
                material.setNombreArchivo(archivo.getOriginalFilename());
                material.setMongoId(resultado.archivoId());
                material.setSemana(semana);
                material.setVisible(true);

                materialRepository.save(material);
            } else {
                // Si falla la conversión a vectores, lanzamos error para que el frontend lo
                // sepa
                throw new RuntimeException("Error al procesar el archivo con IA: " + resultado.errorMensaje());
            }
        }
        return obtenerSemana(semanaId);
    }

    public void eliminarMaterial(Long materialId) {
        Material material = materialRepository.findById(materialId)
                .orElseThrow(() -> new RuntimeException("Material no encontrado"));

        // Cascada: Borrar vectores en Qdrant y el archivo bruto en MongoDB
        if (material.getMongoId() != null) {
            ragIngestionService.eliminarVectoresPorArchivoId(material.getMongoId());
            archivoService.eliminarArchivoMongo(material.getMongoId());
        }

        materialRepository.delete(material);
    }

    public boolean toggleVisibilidadMaterial(Long materialId) {
        Material material = materialRepository.findById(materialId)
                .orElseThrow(() -> new RuntimeException("Material no encontrado"));

        material.setVisible(!material.isVisible());
        materialRepository.save(material);

        return material.isVisible();
    }
}