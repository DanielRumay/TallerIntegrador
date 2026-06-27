package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Data
public class SoporteRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String correo;

    @Column(nullable = false)
    private String motivo;

    @Column(columnDefinition = "TEXT")
    private String descripcion;

    @Column(nullable = false)
    private LocalDateTime fechaCreacion;

    @PrePersist
    public void prePersist() {
        if (fechaCreacion == null) {
            fechaCreacion = LocalDateTime.now();
        }
    }
}
