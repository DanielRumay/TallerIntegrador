package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.entidades.mongodb.ArchivoPrompt;
import com.example.tallerintegrador.service.EvaluacionIAService;

import com.example.tallerintegrador.service.SpikeService;
import lombok.RequiredArgsConstructor;

import org.apache.tika.exception.TikaException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/archivos")
@RequiredArgsConstructor
public class EvaluacionIAController {

    private final EvaluacionIAService evaluacionIAService;
    private final SpikeService spikeService;

    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @PostMapping("/subir")
    public ResponseEntity<?> subirArchivos(
            @RequestParam("archivos") List<MultipartFile> archivos) {
        try {
            evaluacionIAService.guardarArchivos(archivos);
            return ResponseEntity.ok("Archivos guardados");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @GetMapping("/listar")
    public ResponseEntity<?> listarArchivos() {
        return ResponseEntity.ok(evaluacionIAService.listarArchivos());
    }

    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @PostMapping("/una-tecnica-pdf-id")
    public ResponseEntity<?> unaTecnicaPdfId(
            @RequestParam String mongoId,
            @RequestParam(defaultValue = "OPCION_MULTIPLE") String tipo,
            @RequestParam(defaultValue = "3") int cantidad) {
        try {
            return ResponseEntity.ok(spikeService.ejecutarTecnicaConPdfId(mongoId, tipo, cantidad));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error: " + e.getMessage());
        }
    }

    @PreAuthorize("hasAuthority('TEACHER') or hasAuthority('STUDENT')")
    @GetMapping(value = "/stream-tecnica-pdf", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter unaTecnicaPdfIdStream(
            @RequestParam String mongoId,
            @RequestParam(defaultValue = "OPCION_MULTIPLE") String tipo,
            @RequestParam(defaultValue = "3") int cantidad) {
        SseEmitter emitter = new SseEmitter(600_000L);
        CompletableFuture.runAsync(() -> {
            try {
                spikeService.ejecutarTecnicaConPdfIdStream(mongoId, tipo, cantidad, emitter);
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data("Error: " + e.getMessage()));
                } catch (IOException ignored) {}
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}