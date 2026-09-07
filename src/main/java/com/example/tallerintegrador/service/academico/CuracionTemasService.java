package com.example.tallerintegrador.service.academico;

import com.example.tallerintegrador.entidades.postgres.CuracionTema;
import com.example.tallerintegrador.entidades.postgres.Material;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.repository.CuracionTemaRepository;
import com.example.tallerintegrador.repository.MaterialRepository;
import com.example.tallerintegrador.repository.UserRepository;
import com.example.tallerintegrador.service.rag.RagRetrieverService;
import com.example.tallerintegrador.service.util.NormalizadorConcepto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Curación de los temas extraídos automáticamente, por parte del docente.
 *
 * PROBLEMA QUE RESUELVE. Los subtemas se extraen sección por sección, y una sección puede
 * caer sobre un índice, una portada o una bibliografía. El modelo, obligado a responder,
 * inventa temas de ahí: "Índice de contenidos", "Editorial Santillana", "Página 47". Esos
 * temas llegan hoy al alumno como chips de práctica.
 *
 * Ninguna heurística automática los distingue con fiabilidad de un tema real. Un docente sí,
 * en dos segundos — pero necesita ver DE DÓNDE salió cada tema para juzgarlo, y eso es lo
 * que aporta este servicio: junto a cada tema devuelve los fragmentos del documento que lo
 * originaron.
 *
 * SUBPRODUCTO VALIOSO: la proporción de temas aceptados es una medida de calidad de la
 * extracción **validada por un experto**, reportable en el informe.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CuracionTemasService {

    private final CuracionTemaRepository curacionRepository;
    private final MaterialRepository materialRepository;
    private final UserRepository userRepository;
    private final ArchivoService archivoService;
    private final RagRetrieverService ragRetrieverService;

    /** Fragmentos que se muestran como evidencia de cada tema. */
    private static final int FRAGMENTOS_DE_MUESTRA = 3;

    /** Recorte del fragmento: lo justo para reconocer de qué habla sin llenar la pantalla. */
    private static final int LONGITUD_MUESTRA = 400;

    public record TemaCurable(
            String tema,
            /** null = el docente aún no ha decidido. */
            Boolean aceptado,
            String motivo,
            /** Fragmentos del documento que originaron este tema. */
            List<String> evidencia
    ) {}

    /**
     * Temas de un material con su estado de curación y la evidencia que los respalda.
     *
     * Los temas SIN decidir se devuelven con `aceptado = null` y NO se ocultan al alumno:
     * el criterio por defecto es mostrar. Ocultar lo no revisado dejaría al alumno sin temas
     * mientras el docente no entre a validarlos, que sería peor que dejar pasar alguno malo.
     */
    @Transactional(readOnly = true)
    public List<TemaCurable> temasDe(Long materialId) {
        Material material = materialRepository.findById(materialId)
                .orElseThrow(() -> new IllegalArgumentException("Material no encontrado: " + materialId));

        List<String> subtemas = archivoService.obtenerSubtemas(material.getMongoId());
        if (subtemas == null || subtemas.isEmpty()) return List.of();

        Map<String, CuracionTema> decisiones = new HashMap<>();
        for (CuracionTema c : curacionRepository.findByMaterialId(materialId)) {
            decisiones.put(c.getTemaCanonico(), c);
        }

        List<TemaCurable> salida = new ArrayList<>();
        for (String tema : subtemas) {
            CuracionTema decision = decisiones.get(NormalizadorConcepto.canonizar(tema));
            salida.add(new TemaCurable(
                    tema,
                    decision != null ? decision.isAceptado() : null,
                    decision != null ? decision.getMotivo() : null,
                    evidenciaDe(tema, material.getMongoId())));
        }
        return salida;
    }

    /**
     * Busca en el índice vectorial los fragmentos más cercanos al tema.
     *
     * Es la clave para que el docente pueda juzgar: leyendo el texto real ve enseguida si
     * "Índice de contenidos" salió de una lista de capítulos o si es un tema de verdad.
     */
    private List<String> evidenciaDe(String tema, String mongoId) {
        try {
            return ragRetrieverService.recuperar(tema, mongoId).stream()
                    .limit(FRAGMENTOS_DE_MUESTRA)
                    .map(c -> {
                        String t = c.texto();
                        return t.length() > LONGITUD_MUESTRA ? t.substring(0, LONGITUD_MUESTRA) + "…" : t;
                    })
                    .toList();
        } catch (Exception e) {
            // Sin evidencia el docente aún puede decidir por el nombre del tema; quedarse sin
            // la pantalla entera por un fallo de Qdrant sería mucho peor.
            log.warn("[CURACION] Sin evidencia para '{}': {}", tema, e.getMessage());
            return List.of();
        }
    }

    /** Registra la decisión del docente. Vuelve a llamarse para cambiarla de opinión. */
    @Transactional
    public void decidir(Long materialId, String tema, boolean aceptado, String motivo, Long docenteId) {
        String canonico = NormalizadorConcepto.canonizar(tema);
        if (canonico.isEmpty()) {
            throw new IllegalArgumentException("El tema está vacío");
        }

        Material material = materialRepository.findById(materialId)
                .orElseThrow(() -> new IllegalArgumentException("Material no encontrado: " + materialId));

        CuracionTema registro = curacionRepository
                .findByMaterialIdAndTemaCanonico(materialId, canonico)
                .orElseGet(CuracionTema::new);

        registro.setMaterial(material);
        registro.setTemaCanonico(canonico);
        registro.setTemaEtiqueta(NormalizadorConcepto.paraMostrar(tema));
        registro.setAceptado(aceptado);
        registro.setMotivo(motivo);
        registro.setFecha(java.time.LocalDateTime.now());
        if (docenteId != null) {
            userRepository.findById(docenteId).ifPresent(registro::setDocente);
        }
        curacionRepository.save(registro);

        log.info("[CURACION] Material {} · tema '{}' → {}", materialId, canonico,
                aceptado ? "ACEPTADO" : "DESCARTADO");
    }

    /**
     * Temas descartados de un conjunto de materiales, en forma canónica.
     *
     * Lo usa el mapeador de semanas para no enviarlos al alumno. Se devuelve un conjunto de
     * claves canónicas y no de etiquetas para que la comparación no dependa de mayúsculas ni
     * de tildes.
     */
    @Transactional(readOnly = true)
    public Set<String> temasOcultosDe(List<Long> materialIds) {
        if (materialIds == null || materialIds.isEmpty()) return Set.of();
        return curacionRepository.findByMaterialIdInAndAceptadoFalse(materialIds).stream()
                .map(CuracionTema::getTemaCanonico)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Métrica reportable: qué proporción de los temas extraídos automáticamente valida un
     * docente. Es una medida de calidad de la extracción hecha por un experto, no por el
     * propio sistema.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> tasaDeAceptacion() {
        long aceptados = curacionRepository.countByAceptadoTrue();
        long descartados = curacionRepository.countByAceptadoFalse();
        long revisados = aceptados + descartados;

        Map<String, Object> salida = new LinkedHashMap<>();
        salida.put("temasRevisados", revisados);
        salida.put("aceptados", aceptados);
        salida.put("descartados", descartados);
        salida.put("tasaAceptacion", revisados == 0 ? null
                : Math.round((aceptados * 1000.0) / revisados) / 1000.0);
        salida.put("interpretacion", revisados == 0
                ? "Ningún docente ha revisado temas todavía."
                : String.format("El docente validó el %.1f%% de los temas extraídos automáticamente (%d de %d). "
                        + "Mide la calidad de la extracción según un experto; NO mide si los temas "
                        + "aceptados están bien redactados ni si cubren todo el material.",
                        (aceptados * 100.0) / revisados, aceptados, revisados));
        return salida;
    }
}
