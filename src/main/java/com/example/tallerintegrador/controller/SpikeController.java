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
    //private final TikaExtractorService tikaExtractorService;


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
            @RequestParam(required = false)                 String nivelBloom,
            @RequestParam(defaultValue = "2")              int cantidad) {

        if (archivos == null || archivos.isEmpty())
            return ResponseEntity.badRequest().body("No se enviaron archivos.");

        try {
            List<String> nombres = archivos.stream()
                    .map(MultipartFile::getOriginalFilename)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                    "archivos_procesados", nombres,
                    "resultados", spikeService.compareConPdfs(archivos, tipo, nivelBloom, cantidad)
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body("Error procesando PDFs: " + e.getMessage());
        }
    }

    // Este endpoint no cambia nada
    @PostMapping("/una-tecnica")
    public ResponseEntity<Map<String, Object>> unaTecnica(
            @RequestBody UnaTecnicaRequest req) {
        Map<String, Object> resultado = spikeService.ejecutarTecnica(
                req.tecnica(), req.tipo(), req.nivelBloom(), req.texto(),
                req.cantidad() != null ? req.cantidad() : 3);
        return ResponseEntity.ok(resultado);
    }

    // ✅ CAMBIA: ya no usa Tika, llama directo a ejecutarTecnicaConPdfs
    @PostMapping("/una-tecnica-pdf")
    public ResponseEntity<?> unaTecnicaPdf(
            @RequestParam("archivos") List<MultipartFile> archivos,
            @RequestParam(defaultValue = "OPCION_MULTIPLE")  String tipo,
            @RequestParam(defaultValue = "CHAIN_OF_THOUGHT") String tecnica,
            @RequestParam(required = false)                  String nivelBloom,
            @RequestParam(defaultValue = "3")               int cantidad) {

        if (archivos == null || archivos.isEmpty())
            return ResponseEntity.badRequest().body("No se enviaron archivos.");

        try {
            return ResponseEntity.ok(
                    spikeService.ejecutarTecnicaConPdfs(tecnica, tipo, nivelBloom, archivos, cantidad));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body("Error procesando PDFs: " + e.getMessage());
        }
    }

    public record CompararRequest(String texto, String tipo, String nivelBloom, Integer cantidad) {}
    public record UnaTecnicaRequest(String texto, String tipo, String tecnica, String nivelBloom, Integer cantidad) {}
}