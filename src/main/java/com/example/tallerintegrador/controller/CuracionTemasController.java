package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.repository.UserRepository;
import com.example.tallerintegrador.service.academico.CuracionTemasService;
import com.example.tallerintegrador.service.util.IdHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Curación de los temas extraídos automáticamente.
 *
 * El docente ve cada tema junto a los fragmentos del documento que lo originaron, y decide
 * si es un tema real o basura de un índice o una portada. Los descartados dejan de llegar al
 * alumno.
 */
@RestController
@RequestMapping("/curacion-temas")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN')")
public class CuracionTemasController {

    private final CuracionTemasService curacionTemasService;
    private final UserRepository userRepository;
    private final IdHasher idHasher;

    /** Temas de un material, con su estado y la evidencia que los respalda. */
    @GetMapping("/material/{materialId}")
    public ResponseEntity<?> temasDe(@PathVariable String materialId) {
        try {
            return ResponseEntity.ok(curacionTemasService.temasDe(idHasher.decode(materialId)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Acepta o descarta un tema. Volver a llamarlo cambia la decisión. */
    @PostMapping("/material/{materialId}/decidir")
    public ResponseEntity<?> decidir(
            @PathVariable String materialId,
            @RequestBody DecisionRequest req,
            Authentication authentication) {
        try {
            Long docenteId = authentication == null ? null
                    : userRepository.findByCorreo(authentication.getName())
                        .map(u -> u.getId()).orElse(null);

            curacionTemasService.decidir(
                    idHasher.decode(materialId), req.tema(), req.aceptado(), req.motivo(), docenteId);

            return ResponseEntity.ok(Map.of("mensaje",
                    req.aceptado() ? "Tema aceptado" : "Tema descartado; dejará de mostrarse al alumno"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Métrica reportable: qué proporción de los temas extraídos valida un docente.
     * Es una medida de calidad de la extracción hecha por un experto, no por el sistema.
     */
    @GetMapping("/tasa-aceptacion")
    public ResponseEntity<Map<String, Object>> tasaAceptacion() {
        return ResponseEntity.ok(curacionTemasService.tasaDeAceptacion());
    }

    public record DecisionRequest(String tema, boolean aceptado, String motivo) {}
}
