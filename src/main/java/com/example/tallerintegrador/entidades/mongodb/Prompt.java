package com.example.tallerintegrador.entidades.mongodb;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Document(collection = "prompts")
public class Prompt {

    @Id
    private String id;

    private Long usuarioId;

    private String contenido;

    private LocalDateTime fechaCreacion;

    private List<ArchivoPrompt> archivos;
}