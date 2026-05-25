package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.service.AgentJudgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/agent-judge")
@RequiredArgsConstructor
public class AgentJudgeController {

    private final AgentJudgeService agentJudgeService;

    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('STUDENT') or hasAuthority('ADMIN')")
    @PostMapping("/evaluar-respuesta")
    public ResponseEntity<?> evaluarRespuesta(@RequestBody EvaluarRespuestaRequest req) {

        if (req.pregunta() == null || req.respuestaEsperada() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Faltan datos obligatorios."));
        }

        String respuestaEstudiante = (req.respuestaEstudiante() != null && !req.respuestaEstudiante().isBlank())
                ? req.respuestaEstudiante()
                : "El estudiante no proporcionó ninguna respuesta.";

        int total = (req.totalPreguntas() != null && req.totalPreguntas() > 0)
                ? req.totalPreguntas()
                : 1;

        try {
            Map<String, Object> resultado = agentJudgeService.evaluarRespuestaUnitaria(
                    req.pregunta(),
                    req.respuestaEsperada(),
                    respuestaEstudiante,
                    total
            );
            return ResponseEntity.ok(resultado);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Error interno: " + e.getMessage()));
        }
    }

    public record EvaluarRespuestaRequest(
            String pregunta,
            String respuestaEsperada,
            String respuestaEstudiante,
            Integer totalPreguntas
    ) {}
}