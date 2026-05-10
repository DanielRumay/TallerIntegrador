package com.example.tallerintegrador.entidades.mongodb;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Map;

@Document(collection = "prompts")
public class Prompt {

    @Id
    private String id;

    private String titulo;

    private String promptBase;

    private Map<String, Object> variables;

    private LocalDateTime fechaCreacion;

    // Getters y Setters
}