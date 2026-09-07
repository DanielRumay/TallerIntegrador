package com.example.tallerintegrador.service.academico;
import com.example.tallerintegrador.service.rag.RagIngestionService;

import com.example.tallerintegrador.DTO.SemanaDTO;
import com.example.tallerintegrador.entidades.postgres.Curso;
import com.example.tallerintegrador.entidades.postgres.Material;
import com.example.tallerintegrador.entidades.postgres.Semana;
import com.example.tallerintegrador.mapper.SemanaMapper;
import com.example.tallerintegrador.repository.CursoRepository;
import com.example.tallerintegrador.repository.IntentoRepository;
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
    private final CursoRepository cursoRepository;
    private final IntentoRepository intentoRepository;
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

                // Si la semana todavía no tiene un nombre significativo, lo derivamos del
                // primer material que sí trajo subtemas extraídos. "Semana 1" no le dice
                // nada al alumno; "Revolución Francesa · Causas Económicas" sí.
                if ((semana.getNombreTema() == null || semana.getNombreTema().isBlank())) {
                    List<String> subtemas = archivoService.obtenerSubtemas(resultado.archivoId());
                    if (subtemas != null && !subtemas.isEmpty()) {
                        semana.setNombreTema(String.join(" · ", subtemas.subList(0, Math.min(2, subtemas.size()))));
                        semanaRepository.save(semana);
                    }
                }
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

    // =========================================================================
    // GESTIÓN DE SEMANAS (crear / renombrar / eliminar)
    // =========================================================================

    /**
     * Crea una semana nueva al final del curso. El "numSem" ordinal ("Semana N") se
     * calcula automáticamente; nombreTema es opcional en la creación porque normalmente
     * se deriva solo al subir el primer material — pero el docente puede fijarlo desde ya
     * si ya sabe qué tema va a cubrir.
     */
    public SemanaDTO crearSemana(Long cursoId, String nombreTemaInicial) {
        Curso curso = cursoRepository.findById(cursoId)
                .orElseThrow(() -> new RuntimeException("Curso no encontrado"));

        long totalActual = semanaRepository.countByCursoId(cursoId);

        Semana semana = new Semana();
        semana.setCurso(curso);
        semana.setNumSem("Semana " + (totalActual + 1));
        semana.setHabilitada(true);
        if (nombreTemaInicial != null && !nombreTemaInicial.isBlank()) {
            semana.setNombreTema(nombreTemaInicial.trim());
        }

        Semana guardada = semanaRepository.save(semana);
        return semanaMapper.toDTO(guardada);
    }

    public boolean toggleHabilitada(Long semanaId) {
        Semana semana = semanaRepository.findById(semanaId)
                .orElseThrow(() -> new RuntimeException("Semana no encontrada"));

        semana.setHabilitada(!semana.isHabilitada());
        semanaRepository.save(semana);

        return semana.isHabilitada();
    }

    /** Permite al docente corregir o precisar el nombre derivado automáticamente. */
    public SemanaDTO renombrarSemana(Long semanaId, String nuevoNombreTema) {
        Semana semana = semanaRepository.findById(semanaId)
                .orElseThrow(() -> new RuntimeException("Semana no encontrada"));

        semana.setNombreTema(nuevoNombreTema != null ? nuevoNombreTema.trim() : null);
        Semana guardada = semanaRepository.save(semana);
        return semanaMapper.toDTO(guardada);
    }

    /**
     * Elimina una semana vacía. Se rechaza si ya existen intentos de alumnos registrados
     * contra ella: borrar silenciosamente huérfanaría las notas y respuestas guardadas de
     * quien ya la rindió. Los materiales sí se eliminan en cascada (orphanRemoval en la
     * entidad), incluyendo sus vectores en Qdrant y el archivo bruto en Mongo.
     */
    public void eliminarSemana(Long semanaId) {
        Semana semana = semanaRepository.findById(semanaId)
                .orElseThrow(() -> new RuntimeException("Semana no encontrada"));

        if (intentoRepository.existsBySemanaId(semanaId)) {
            throw new IllegalStateException(
                    "No se puede eliminar: ya hay intentos de alumnos registrados en esta semana.");
        }

        if (semana.getMateriales() != null) {
            for (Material material : semana.getMateriales()) {
                if (material.getMongoId() != null) {
                    ragIngestionService.eliminarVectoresPorArchivoId(material.getMongoId());
                    archivoService.eliminarArchivoMongo(material.getMongoId());
                }
            }
        }

        semanaRepository.delete(semana);
    }
}