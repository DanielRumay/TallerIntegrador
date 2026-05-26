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
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class SemanaService {

    private final SemanaRepository semanaRepository;
    private final EvaluacionIAService evaluacionIAService;
    private final MaterialRepository materialRepository;

    private final SemanaMapper semanaMapper;

    public SemanaDTO obtenerSemana(Long semanaId) {
        Semana semana = semanaRepository.findById(semanaId)
                .orElseThrow(() -> new RuntimeException("Semana no encontrada"));
        return semanaMapper.toDTO(semana);
    }

    public SemanaDTO subirArchivos(Long semanaId, List<MultipartFile> archivos) {
        Semana semana = semanaRepository.findById(semanaId)
                .orElseThrow(() -> new RuntimeException("Semana no encontrada"));

        for (MultipartFile archivo : archivos) {
            String mongoId = evaluacionIAService.guardarArchivoYRetornarId(archivo);

            Material material = new Material();
            material.setNombreArchivo(archivo.getOriginalFilename());
            material.setMongoId(mongoId);
            material.setSemana(semana);

            materialRepository.save(material);
        }
        return obtenerSemana(semanaId);
    }

    public void eliminarMaterial(Long materialId) {
        Material material = materialRepository.findById(materialId)
                .orElseThrow(() -> new RuntimeException("Material no encontrado"));
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