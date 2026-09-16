package com.example.tallerintegrador.service.rag;

import com.example.tallerintegrador.entidades.postgres.Material;
import com.example.tallerintegrador.repository.MaterialRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Decide EN QUÉ MATERIALES se buscan los fragmentos para generar preguntas.
 *
 * EL PROBLEMA. Los modos de práctica (preguntas, tutor de voz, video) reciben en la URL el id
 * de UN material: la pantalla de la semana tomaba el primero de la lista y pasaba solo ese. El
 * buscador filtraba por él, así que en una semana con dos materiales —por ejemplo
 * "Clases de determinantes" y "El signo lingüístico"— todas las preguntas salían del primero
 * y el segundo no existía para el sistema. Si el alumno elegía un subtema del segundo, se
 * buscaba dentro del primero, y lo que se encontraba era irrelevante o el modelo rellenaba.
 *
 * POR QUÉ SE ARREGLA AQUÍ Y NO EN LA URL. Ese id se usa también, en otros puntos, para leer
 * UN archivo concreto de Mongo (el nombre, o el texto completo cuando no hay índice). Mandar
 * varios ids separados por comas rompería esas lecturas. Así que la URL sigue trayendo un id,
 * y solo la BÚSQUEDA se amplía a toda la semana.
 *
 * SOLO MATERIALES VISIBLES. Lo que la docente oculta no debe convertirse en preguntas.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlcanceMaterialesService {

    private final MaterialRepository materialRepository;

    /**
     * Dado el id de un material, devuelve los ids de todos los materiales VISIBLES de su semana,
     * separados por comas (formato que ya entiende RagRetrieverService).
     *
     * Si el id no corresponde a un material registrado —por ejemplo, el mongoId propio de una
     * semana antigua— se devuelve tal cual: es el comportamiento que había y no hay nada mejor
     * que hacer sin saber la semana.
     */
    public String ampliarASemana(String mongoId) {
        if (mongoId == null || mongoId.isBlank()) return mongoId;

        var material = materialRepository.findByMongoId(mongoId.strip()).orElse(null);
        if (material == null || material.getSemana() == null) {
            return mongoId;
        }

        List<Material> deLaSemana = materialRepository.findBySemanaId(material.getSemana().getId());
        String visibles = deLaSemana.stream()
                .filter(Material::isVisible)
                .map(Material::getMongoId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .collect(Collectors.joining(","));

        if (visibles.isBlank()) {
            // Todo oculto: se conserva el id recibido en vez de buscar sin filtro, que
            // recorrería los materiales de TODOS los cursos.
            log.warn("[ALCANCE] La semana {} no tiene materiales visibles; se busca solo en {}",
                    material.getSemana().getId(), mongoId);
            return mongoId;
        }

        log.info("[ALCANCE] Búsqueda ampliada de {} a los materiales visibles de la semana {}: {}",
                mongoId, material.getSemana().getId(), visibles);
        return visibles;
    }
}
