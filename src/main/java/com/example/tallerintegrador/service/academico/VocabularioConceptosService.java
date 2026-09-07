package com.example.tallerintegrador.service.academico;

import com.example.tallerintegrador.entidades.postgres.Material;
import com.example.tallerintegrador.repository.MaterialRepository;
import com.example.tallerintegrador.service.util.AlineadorConceptos;
import com.example.tallerintegrador.service.util.NormalizadorConcepto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * El vocabulario oficial de conceptos de una semana: los subtemas de su material que el
 * docente NO descartó.
 *
 * POR QUÉ EXISTE. Hasta ahora la curación del docente y la medición del alumno vivían en
 * mundos separados. El docente descartaba "Literatura" —extraída del catálogo de otras obras
 * impreso al final del PDF— y esa etiqueta seguía apareciendo en el mapa de calor, porque el
 * mapa se alimenta de los conceptos que el generador pone en cada pregunta, no de los
 * subtemas curados.
 *
 * Este servicio es el puente: recoge lo que el docente aprobó y lo usa para agrupar los
 * conceptos que llegan del generador. A partir de aquí, curar un tema SÍ cambia lo que se
 * mide.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VocabularioConceptosService {

    private final MaterialRepository materialRepository;
    private final ArchivoService archivoService;
    private final CuracionTemasService curacionTemasService;

    /**
     * Subtemas aprobados del material de una semana.
     *
     * Los descartados se quitan comparando por forma canónica, no por texto literal: el
     * docente descarta "Literatura" y el generador podría escribir "literatura" o
     * "Literaturas". Comparar cadenas crudas dejaría pasar esas variantes.
     */
    @Transactional(readOnly = true)
    public List<String> deSemana(Long semanaId) {
        if (semanaId == null) return List.of();

        List<Material> materiales = materialRepository.findBySemanaId(semanaId);
        if (materiales.isEmpty()) return List.of();

        Set<String> ocultos = curacionTemasService.temasOcultosDe(
                materiales.stream().map(Material::getId).toList());

        // LinkedHashSet: dos materiales de la misma semana pueden repetir un subtema y no
        // tiene sentido que aparezca dos veces en el vocabulario.
        Set<String> vocabulario = new LinkedHashSet<>();
        for (Material m : materiales) {
            List<String> subtemas = archivoService.obtenerSubtemas(m.getMongoId());
            if (subtemas == null) continue;
            for (String s : subtemas) {
                if (s == null || s.isBlank()) continue;
                if (ocultos.contains(NormalizadorConcepto.canonizar(s))) continue;
                vocabulario.add(s);
            }
        }
        return new ArrayList<>(vocabulario);
    }

    /**
     * Agrupa los conceptos de una pregunta dentro del vocabulario curado de su semana.
     *
     * Nunca lanza. Si algo falla —material borrado, Mongo caído— se devuelven los conceptos
     * tal como venían: perder el agrupamiento es un inconveniente, perder el intento del
     * alumno por eso no tendría ninguna justificación.
     */
    @Transactional(readOnly = true)
    public String alinear(String conceptosCrudos, Long semanaId) {
        try {
            List<String> vocabulario = deSemana(semanaId);
            AlineadorConceptos.Resultado r = AlineadorConceptos.alinear(conceptosCrudos, vocabulario);

            if (r.alineados() > 0 || r.sinEncaje() > 0) {
                log.debug("[VOCABULARIO] Semana {}: {} conceptos agrupados, {} sin encaje",
                        semanaId, r.alineados(), r.sinEncaje());
            }
            return r.conceptos();
        } catch (Exception e) {
            log.warn("[VOCABULARIO] No se pudo alinear los conceptos de la semana {}: {}",
                    semanaId, e.getMessage());
            return conceptosCrudos;
        }
    }
}
