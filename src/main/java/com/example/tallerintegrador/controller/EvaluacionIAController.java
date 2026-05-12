package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.entidades.mongodb.Prompt;
import com.example.tallerintegrador.service.EvaluacionIAService;
import lombok.RequiredArgsConstructor;
import org.apache.tika.exception.TikaException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/evaluacion")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class EvaluacionIAController {

    private final EvaluacionIAService evaluacionIAService;

    @PostMapping("/subir-pdf")
    public Prompt subirPDF(
            @RequestParam MultipartFile archivo,
            @RequestParam Long usuarioId,
            @RequestParam String tecnica,
            @RequestParam String tipoPregunta,
            @RequestParam String nivelBloom,
            @RequestParam int cantidad,
            @RequestParam(required = false) String promptUsuario
    ) throws IOException, TikaException {

        return evaluacionIAService.procesarArchivo(
                archivo,
                usuarioId,
                tecnica,
                tipoPregunta,
                nivelBloom,
                cantidad,
                promptUsuario
        );
    }
}