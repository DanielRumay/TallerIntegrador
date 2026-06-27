package com.example.tallerintegrador.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/rendimiento")
@RequiredArgsConstructor
public class RendimientoController {

    @PreAuthorize("hasAuthority('STUDENT') or hasAuthority('TEACHER') or hasAuthority('ADMIN')")
    @GetMapping("/mapa-calor/{usuarioId}")
    }
}
