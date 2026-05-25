package com.example.tallerintegrador.entidades.mongodb;

import org.springframework.data.annotation.Id;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Document;

@Setter @Getter
@Document(collection = "archivos")
public class ArchivoPrompt {

    @Id
    private String id;

    private String nombre;

    private String tipo;

    private String url;

    private byte[] archivoFisico;
}