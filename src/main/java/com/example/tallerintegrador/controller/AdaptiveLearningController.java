package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.DTO.GuardarIntentoAdaptativoRequest;
import com.example.tallerintegrador.service.AdaptiveLearningService;
import com.example.tallerintegrador.service.util.IdHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/adaptive")
@RequiredArgsConstructor
public class AdaptiveLearningController {

    private final AdaptiveLearningService adaptiveLearningService;
    private final IdHasher idHasher;

    @PreAuthorize("hasAuthority('STUDENT')")
    @GetMapping("/evaluacion")
    public ResponseEntity<?> obtenerEvaluacionAdaptativa(
            @RequestParam Long usuarioId,
            @RequestParam String semanaId) {
        try {
            return ResponseEntity.ok(adaptiveLearningService.generarEvaluacionAdaptativa(usuarioId, idHasher.decode(semanaId)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PreAuthorize("hasAuthority('STUDENT')")
    @PostMapping("/guardar")
    public ResponseEntity<?> guardarIntentoAdaptativo(@RequestBody GuardarIntentoAdaptativoRequest request) {
        try {
            Map<String, Object> resultadoDebate = adaptiveLearningService.guardarIntentoConDebate(request);
            return ResponseEntity.ok(resultadoDebate);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PreAuthorize("hasAuthority('STUDENT')")
    @GetMapping("/materiales-recomendados")
    public ResponseEntity<?> obtenerMaterialesRecomendados(
            @RequestParam Long usuarioId,
            @RequestParam String semanaId) {
        try {
            return ResponseEntity.ok(adaptiveLearningService.recomendarMateriales(usuarioId, idHasher.decode(semanaId)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
