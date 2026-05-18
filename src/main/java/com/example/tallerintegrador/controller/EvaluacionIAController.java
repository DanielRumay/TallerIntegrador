package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.service.EvaluacionIAService;

import lombok.RequiredArgsConstructor;

import org.apache.tika.exception.TikaException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/archivos")
@RequiredArgsConstructor
public class EvaluacionIAController {

    private final EvaluacionIAService evaluacionIAService;

    @PostMapping("/subir")
    public ResponseEntity<?> subirArchivos(
            @RequestParam("archivos") List<MultipartFile> archivos
    ) {

        try {

            System.out.println("1. Entró al controller");

            evaluacionIAService.guardarArchivos(archivos);

            return ResponseEntity.ok("Archivos guardados");

        } catch (Exception e) {

            System.out.println("3. ERROR:");
            e.printStackTrace();

            return ResponseEntity.internalServerError()
                    .body(e.getMessage());
        }
    }
}