package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.DTO.GuardarIntentoRequest;
import com.example.tallerintegrador.service.AdaptiveService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/adaptive")
@RequiredArgsConstructor
public class AdaptiveController {

    private final AdaptiveService adaptiveService;

    /**
     * GET /api/adaptive/evaluacion?usuarioId={id}&semanaId={id}
     * Devuelve la evaluacion que le corresponde al alumno:
     *  - DIAGNOSTICA (ACRA Likert) si es su primer intento en la semana
     *  - FORMATIVA adaptativa (generada por el OrchestratorAgent) si ya tiene intentos previos
     */
    @PreAuthorize("hasAuthority('STUDENT') or hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @GetMapping("/evaluacion")
    public ResponseEntity<?> obtenerEvaluacion(
            @RequestParam Long usuarioId,
            @RequestParam Long semanaId) {
        try {
            return ResponseEntity.ok(adaptiveService.obtenerEvaluacion(usuarioId, semanaId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/adaptive/guardar
     * Guarda el intento del alumno, genera el debate de agentes con Gemini,
     * calcula su nuevo nivel y devuelve la transcripcion del debate.
     */
    @PreAuthorize("hasAuthority('STUDENT') or hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @PostMapping("/guardar")
    public ResponseEntity<?> guardarIntento(@RequestBody GuardarIntentoRequest request) {
        try {
            return ResponseEntity.ok(adaptiveService.guardarIntentoAdaptativo(request));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/adaptive/materiales-recomendados?usuarioId={id}&semanaId={id}
     * Devuelve los materiales visibles de la semana para que el alumno pueda repasar.
     */
    @PreAuthorize("hasAuthority('STUDENT') or hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @GetMapping("/materiales-recomendados")
    public ResponseEntity<?> getMaterialesRecomendados(
            @RequestParam Long usuarioId,
            @RequestParam Long semanaId) {
        try {
            return ResponseEntity.ok(adaptiveService.obtenerMaterialesRecomendados(usuarioId, semanaId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
