package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.service.metricas.MetricasIAService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Panel de métricas del pipeline de IA.
 *
 * Expone como API lo que hasta ahora solo se afirmaba en el informe: conformidad Bloom,
 * eficacia de la deduplicación, degradación del RAG, coste y robustez del juez, y tasa de
 * veto del comité. Restringido a docentes y administradores porque agrega el
 * comportamiento del sistema sobre todos los alumnos.
 */
@RestController
@RequestMapping("/metricas")
@RequiredArgsConstructor
@Tag(name = "Métricas de IA", description = "Indicadores verificables del pipeline de generación, calificación y adaptación")
public class MetricasController {

    private final MetricasIAService metricasIAService;

    /** Ventana por defecto cuando el cliente no especifica fechas: últimos 30 días. */
    private static final int DIAS_POR_DEFECTO = 30;

    @Operation(summary = "Panel consolidado de todos los indicadores del pipeline de IA")
    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @GetMapping("/resumen")
    public ResponseEntity<Map<String, Object>> resumen(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime hasta) {
        return ResponseEntity.ok(metricasIAService.resumen(desde(desde), hasta(hasta)));
    }

    @Operation(summary = "OE1 — conformidad declarada con la Taxonomía de Bloom y proporción de HOTS")
    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @GetMapping("/bloom")
    public ResponseEntity<Map<String, Object>> bloom(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime hasta) {
        return ResponseEntity.ok(metricasIAService.conformidadBloom(desde(desde), hasta(hasta)));
    }

    @Operation(summary = "Eficacia real del filtro de deduplicación semántica")
    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @GetMapping("/deduplicacion")
    public ResponseEntity<Map<String, Object>> deduplicacion(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime hasta) {
        return ResponseEntity.ok(metricasIAService.deduplicacion(desde(desde), hasta(hasta)));
    }

    @Operation(summary = "Distribución de umbrales efectivos y tasa de degradación del RAG")
    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @GetMapping("/rag")
    public ResponseEntity<Map<String, Object>> rag(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime hasta) {
        return ResponseEntity.ok(metricasIAService.rag(desde(desde), hasta(hasta)));
    }

    @Operation(summary = "Legibilidad (Fernández-Huerta) y riqueza léxica (TTR) de las preguntas generadas")
    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @GetMapping("/calidad-textual")
    public ResponseEntity<Map<String, Object>> calidadTextual(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime hasta) {
        return ResponseEntity.ok(metricasIAService.calidadTextual(desde(desde), hasta(hasta)));
    }

    @Operation(summary = "Tasa de validación de las imágenes pedagógicas generadas por IA")
    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @GetMapping("/imagenes")
    public ResponseEntity<Map<String, Object>> imagenes(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime hasta) {
        return ResponseEntity.ok(metricasIAService.imagenes(desde(desde), hasta(hasta)));
    }

    @Operation(summary = "Reactivos rechazados por referencia a la estructura del documento fuente")
    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @GetMapping("/guardia-enunciado")
    public ResponseEntity<Map<String, Object>> guardiaEnunciado(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime hasta) {
        return ResponseEntity.ok(metricasIAService.guardiaEnunciado(desde(desde), hasta(hasta)));
    }

    @Operation(summary = "Latencia, coste en tokens y robustez de parseo del agente juez")
    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @GetMapping("/juez")
    public ResponseEntity<Map<String, Object>> juez(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime hasta) {
        return ResponseEntity.ok(metricasIAService.juez(desde(desde), hasta(hasta)));
    }

    @Operation(summary = "Tasa de veto, transiciones de nivel y latencia del comité de agentes")
    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @GetMapping("/comite")
    public ResponseEntity<Map<String, Object>> comite(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime hasta) {
        return ResponseEntity.ok(metricasIAService.comite(desde(desde), hasta(hasta)));
    }

    @Operation(summary = "Historial de deliberaciones del comité para un alumno, con su transcripción")
    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @GetMapping("/comite/alumno/{usuarioId}")
    public ResponseEntity<List<Map<String, Object>>> debatesDeAlumno(@PathVariable Long usuarioId) {
        return ResponseEntity.ok(metricasIAService.debatesDeAlumno(usuarioId));
    }

    @Operation(summary = "Exporta la telemetría cruda en CSV para los anexos de la tesis")
    @PreAuthorize("hasAuthority('ADMIN')")
    @GetMapping(value = "/exportar", produces = "text/csv")
    public ResponseEntity<String> exportar(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime hasta) {
        String csv = metricasIAService.exportarCsv(desde(desde), hasta(hasta));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"metricas-ia.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv);
    }

    private LocalDateTime desde(LocalDateTime desde) {
        return desde != null ? desde : LocalDateTime.now().minusDays(DIAS_POR_DEFECTO);
    }

    private LocalDateTime hasta(LocalDateTime hasta) {
        return hasta != null ? hasta : LocalDateTime.now();
    }
}
