package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.service.SpikeService;
import com.example.tallerintegrador.service.TikaExtractorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

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
            @RequestParam("archivo")                         MultipartFile archivo,
            @RequestParam(defaultValue = "OPCION_MULTIPLE") String tipo,
            @RequestParam(required = false)                  String nivelBloom,
            @RequestParam(defaultValue = "2")               int cantidad) {

        if (archivo.isEmpty())
            return ResponseEntity.badRequest().body("El archivo está vacío.");

        String texto;
        try {
            texto = tikaExtractorService.extractText(archivo);
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body("Error extrayendo texto con Tika: " + e.getMessage());
        }

        return ResponseEntity.ok(Map.of(
                "archivo",    archivo.getOriginalFilename(),
                "chars_extraidos", texto.length(),
                "resultados", spikeService.compare(texto, tipo, nivelBloom, cantidad)
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
            @RequestParam("archivo")                         MultipartFile archivo,
            @RequestParam(defaultValue = "OPCION_MULTIPLE") String tipo,
            @RequestParam(defaultValue = "CHAIN_OF_THOUGHT") String tecnica,
            @RequestParam(required = false)                  String nivelBloom,
            @RequestParam(defaultValue = "3")               int cantidad) {

        if (archivo.isEmpty())
            return ResponseEntity.badRequest().body("El archivo está vacío.");

        String texto;
        try {
            texto = tikaExtractorService.extractText(archivo);
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body("Error extrayendo texto con Tika: " + e.getMessage());
        }

        return ResponseEntity.ok(spikeService.ejecutarTecnica(
                tecnica, tipo, nivelBloom, texto, cantidad));
    }

    public record CompararRequest(String texto, String tipo, String nivelBloom, Integer cantidad) {}
    public record UnaTecnicaRequest(String texto, String tipo, String tecnica, String nivelBloom, Integer cantidad) {}
}