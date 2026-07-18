package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.agents.TutorConversacionalAgent;
import com.example.tallerintegrador.service.ArchivoService;

import com.example.tallerintegrador.agents.EvaluationOrchestratorAgent;
import com.example.tallerintegrador.service.RagIngestionService;
import com.example.tallerintegrador.service.SpikeService;
import lombok.RequiredArgsConstructor;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/archivos")
@RequiredArgsConstructor
public class ArchivosIaController {

    private final ArchivoService archivoService;
    private final SpikeService spikeService;
    private final RagIngestionService ragIngestionService;
    private final EvaluationOrchestratorAgent evaluationOrchestratorAgent;
    private final TutorConversacionalAgent tutorConversacionalAgent;

    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @PostMapping("/subir")
    public ResponseEntity<?> subirArchivos(
            @RequestParam("archivos") List<MultipartFile> archivos) {
        if (archivos != null) {
            for (MultipartFile archivo : archivos) {
                if (archivo.getSize() > 15 * 1024 * 1024) {
                    return ResponseEntity.badRequest().body("El archivo " + archivo.getOriginalFilename() + " excede el límite de 15MB.");
                }
            }
        }
        try {
            archivoService.guardarArchivos(archivos);
            return ResponseEntity.ok("Archivos guardados");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @GetMapping("/listar")
    public ResponseEntity<?> listarArchivos() {
        return ResponseEntity.ok(archivoService.listarArchivos());
    }

    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN') or hasAuthority('STUDENT')")
    @PostMapping("/una-tecnica-pdf-id")
    public ResponseEntity<?> unaTecnicaPdfId(
            @RequestParam String mongoId,
            @RequestParam(defaultValue = "OPCION_MULTIPLE") String tipo,
            @RequestParam(defaultValue = "3") int cantidad,
            @RequestParam(required = false) String tema) {
        try {
            String correo = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName();
            return ResponseEntity.ok(spikeService.ejecutarTecnicaConPdfId(mongoId, tipo, cantidad, tema, correo));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }

    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('STUDENT')")
    @GetMapping(value = "/stream-tecnica-pdf", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> unaTecnicaPdfIdStream(
            @RequestParam String mongoId,
            @RequestParam(defaultValue = "OPCION_MULTIPLE") String tipo,
            @RequestParam(defaultValue = "3") int cantidad,
            @RequestParam(required = false) String tema) {
        String correo = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName();
        SseEmitter emitter = new SseEmitter(600_000L);
        CompletableFuture.runAsync(() -> {
            try {
                spikeService.ejecutarTecnicaConPdfIdStream(mongoId, tipo, cantidad, tema, emitter, correo);
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data("Error: " + e.getMessage()));
                } catch (IOException ignored) {}
                emitter.complete();
            }
        });
        return ResponseEntity.ok()
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('STUDENT')")
    @GetMapping("/generar-imagen")
    public ResponseEntity<?> generarImagen(@RequestParam String prompt) {
        try {
            String base64 = spikeService.generarImagenDirecta(prompt);
            return ResponseEntity.ok(Map.of("base64", base64));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/ingestar")
    public ResponseEntity<?> ingestarArchivo(@RequestParam("archivo") MultipartFile archivo) {
        if (archivo == null || archivo.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Archivo vacío o no proporcionado."));
        }
        if (archivo.getSize() > 15 * 1024 * 1024) {
            return ResponseEntity.badRequest().body(Map.of("error", "El archivo excede el límite de 15MB."));
        }
        var resultado = ragIngestionService.ingestarArchivo(archivo);
        return resultado.exitoso()
                ? ResponseEntity.ok(resultado)
                : ResponseEntity.status(500).body(Map.of("error", resultado.errorMensaje()));
    }

    @PostMapping("/generar-evaluacion")
    public ResponseEntity<?> generarEvaluacion(@RequestBody GenerarEvaluacionRequest req) {
        String correo = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName();
        var resultado = evaluationOrchestratorAgent.generarEvaluacion(
                req.tema(),
                req.archivoId(),
                req.tipoPregunta(),
                req.nivelBloom(),
                req.tecnica(),
                req.cantidad(),
                correo
        );
        return ResponseEntity.ok(resultado);
    }

    @PostMapping("/evaluar-respuestas")
    public ResponseEntity<?> evaluarRespuestas(@RequestBody List<Map<String, String>> respuestas) {
        return ResponseEntity.ok(evaluationOrchestratorAgent.evaluarRespuestas(respuestas));
    }

    // Records para los requests
    public record GenerarEvaluacionRequest(
            String tema,
            String archivoId,    // puede ser null
            String tipoPregunta,
            String nivelBloom,
            String tecnica,
            int    cantidad
    ) {}

    // Endpoint 1: genera la pregunta del tutor
    @PostMapping("/tutor/pregunta")
    public ResponseEntity<?> generarPreguntaTutor(@RequestBody PreguntaTutorRequest req) {
        String correo = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName();
        return ResponseEntity.ok(
                tutorConversacionalAgent.generarPreguntaTutor(
                        req.tema(), req.mongoId(), req.turno(), req.preguntasEvitar(), correo
                )
        );
    }

    // Endpoint 2: analiza la respuesta oral del estudiante (SSE)
    @PostMapping(value = "/tutor/analizar", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analizarRespuestaOral(@RequestBody AnalisisOralRequest req) {
        SseEmitter emitter = new SseEmitter(120_000L);
        CompletableFuture.runAsync(() ->
                tutorConversacionalAgent.analizarRespuestaOral(
                        req.pregunta(),
                        req.respuestaEstudiante(),
                        req.tema(),
                        req.nivelDificultad(),
                        emitter
                )
        );
        return emitter;
    }

    // Endpoint 3: analiza la respuesta de audio directo del estudiante (SSE)
    @PostMapping(value = "/tutor/analizar-audio", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analizarAudioTutor(
            @RequestParam("audio") MultipartFile audio,
            @RequestParam("pregunta") String pregunta,
            @RequestParam("tema") String tema,
            @RequestParam("nivelDificultad") String nivelDificultad) {
        SseEmitter emitter = new SseEmitter(120_000L);
        CompletableFuture.runAsync(() -> {
            try {
                tutorConversacionalAgent.analizarAudioTutor(
                        pregunta,
                        audio,
                        tema,
                        nivelDificultad,
                        emitter
                );
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data("Error de audio: " + e.getMessage()));
                } catch (IOException ignored) {}
                emitter.complete();
            }
        });
        return emitter;
    }

    // Records:
    public record PreguntaTutorRequest(String tema, String mongoId, int turno, List<String> preguntasEvitar) {}
    public record AnalisisOralRequest(
            String pregunta,
            String respuestaEstudiante,
            String tema,
            String nivelDificultad
    ) {}
}
