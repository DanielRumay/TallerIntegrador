package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.service.metricas.UsoPlataformaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/uso")
@RequiredArgsConstructor
public class UsoPlataformaController {

    private final UsoPlataformaService usoPlataformaService;

    @PreAuthorize("hasAuthority('ADMIN')")
    @GetMapping
    public ResponseEntity<?> resumen(@RequestParam(defaultValue = "30") int dias) {
        return ResponseEntity.ok(usoPlataformaService.resumen(dias));
    }
}
