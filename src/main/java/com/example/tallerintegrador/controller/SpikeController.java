package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.service.SpikeService;
import com.example.tallerintegrador.service.TikaExtractorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/spike")
@RequiredArgsConstructor
public class SpikeController {

    private final SpikeService spikeService;
    private final TikaExtractorService tikaExtractorService;


    @PostMapping("/comparar")
    public ResponseEntity<List<Map<String, Object>>> comparar(
            @RequestBody CompararRequest req) {

        List<Map<String, Object>> resultado = spikeService.compare(
                req.texto(), req.tipo(), req.nivelBloom(), req.cantidad() != null ? req.cantidad() : 2);

        return ResponseEntity.ok(resultado);
    }

    @PostMapping("/comparar-pdf")
    public ResponseEntity<?> compararPdf(
            @RequestParam("archivos") List<MultipartFile> archivos,
            @RequestParam(defaultValue = "OPCION_MULTIPLE") String tipo,
            @RequestParam(required = false)                  String nivelBloom,
            @RequestParam(defaultValue = "2")               int cantidad) {

        if (archivos == null || archivos.isEmpty()) {
            return ResponseEntity.badRequest().body("No se enviaron archivos.");
        }

        String textoCombinado;
        try {
            textoCombinado = tikaExtractorService.extractTextFromMultipleFiles(archivos);
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body("Error extrayendo texto con Tika: " + e.getMessage());
        }

        List<String> nombresArchivos = archivos.stream()
                .map(MultipartFile::getOriginalFilename)
                .collect(Collectors.toList());

        return ResponseEntity.ok(Map.of(
                "archivos_procesados", nombresArchivos,
                "chars_extraidos", textoCombinado.length(),
                "resultados", spikeService.compare(textoCombinado, tipo, nivelBloom, cantidad)
        ));
    }


    @PostMapping("/una-tecnica")
    public ResponseEntity<Map<String, Object>> unaTecnica(
            @RequestBody UnaTecnicaRequest req) {

        Map<String, Object> resultado = spikeService.ejecutarTecnica(
                req.tecnica(), req.tipo(), req.nivelBloom(), req.texto(),
                req.cantidad() != null ? req.cantidad() : 3);

        return ResponseEntity.ok(resultado);
    }


    @PostMapping("/una-tecnica-pdf")
    public ResponseEntity<?> unaTecnicaPdf(
            @RequestParam("archivos") List<MultipartFile> archivos,
            @RequestParam(defaultValue = "OPCION_MULTIPLE") String tipo,
            @RequestParam(defaultValue = "CHAIN_OF_THOUGHT") String tecnica,
            @RequestParam(required = false)                  String nivelBloom,
            @RequestParam(defaultValue = "3")               int cantidad) {

        if (archivos == null || archivos.isEmpty()) {
            return ResponseEntity.badRequest().body("No se enviaron archivos.");
        }

        String textoCombinado;
        try {
            textoCombinado = tikaExtractorService.extractTextFromMultipleFiles(archivos);
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body("Error extrayendo texto con Tika: " + e.getMessage());
        }

        return ResponseEntity.ok(spikeService.ejecutarTecnica(
                tecnica, tipo, nivelBloom, textoCombinado, cantidad));
    }

    public record CompararRequest(String texto, String tipo, String nivelBloom, Integer cantidad) {}
    public record UnaTecnicaRequest(String texto, String tipo, String tecnica, String nivelBloom, Integer cantidad) {}
}