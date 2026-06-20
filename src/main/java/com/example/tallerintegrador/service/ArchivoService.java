package com.example.tallerintegrador.service;

import com.example.tallerintegrador.entidades.mongodb.ArchivoPrompt;
import com.example.tallerintegrador.repository.ArchivoPromptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArchivoService {

    private final ArchivoPromptRepository archivoPromptRepo;

    public void guardarArchivos(List<MultipartFile> archivos) {

        for (MultipartFile archivo : archivos) {
            try {
                ArchivoPrompt archivoPrompt = new ArchivoPrompt();
                archivoPrompt.setNombre(archivo.getOriginalFilename());
                archivoPrompt.setTipo(archivo.getContentType());
                archivoPrompt.setUrl("local/uploads/" + archivo.getOriginalFilename());

                archivoPrompt.setArchivoFisico(archivo.getBytes());

                ArchivoPrompt guardado = archivoPromptRepo.save(archivoPrompt);

                log.info("ÉXITO: Archivo PDF en bruto guardado en Mongo con ID: {}", guardado.getId());

            } catch (Exception e) {
                log.error("Error procesando el archivo {}: {}", archivo.getOriginalFilename(), e.getMessage());
            }
        }
    }
    public List<ArchivoResponse> listarArchivos() {
        // Buscamos todos los archivos en Mongo
        List<ArchivoPrompt> archivos = archivoPromptRepo.findAll();

        // Los mapeamos a nuestro Record para NO enviar los bytes pesados al frontend
        return archivos.stream()
                .map(a -> new ArchivoResponse(a.getId(), a.getNombre(), a.getTipo(), a.getUrl()))
                .toList();
    }
    public String guardarArchivoYRetornarId(MultipartFile archivo) {
        try {
            ArchivoPrompt archivoPrompt = new ArchivoPrompt();
            archivoPrompt.setNombre(archivo.getOriginalFilename());
            archivoPrompt.setTipo(archivo.getContentType());
            archivoPrompt.setUrl("local/uploads/" + archivo.getOriginalFilename());
            archivoPrompt.setArchivoFisico(archivo.getBytes());

            ArchivoPrompt guardado = archivoPromptRepo.save(archivoPrompt);
            log.info("PDF guardado en Mongo con ID: {}", guardado.getId());
            return guardado.getId();
        } catch (Exception e) {
            throw new RuntimeException("Error guardando archivo: " + e.getMessage());
        }
    }

    // DTO Moderno (Record) para enviar solo la información necesaria
    public record ArchivoResponse(String id, String nombre, String tipo, String url) {}
}
