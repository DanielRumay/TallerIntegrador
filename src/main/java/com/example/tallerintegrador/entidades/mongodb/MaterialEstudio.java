package com.example.tallerintegrador.entidades.mongodb;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Document(collection = "materiales_estudio")
public class MaterialEstudio {

    @Id
    private String id;

    private String titulo;

    private String descripcion;

    private String tipo;

    private String contenido;

    private Long cursoId;

    private Long gradoId;

    private List<String> etiquetas;

    private LocalDateTime fechaSubida;

    // Getters y Setters
}