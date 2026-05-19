package com.example.tallerintegrador.entidades.mongodb;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Setter @Getter
@Document(collection = "prompts")
public class Prompt {

    @Id
    private String id;

    private Long usuarioId;

    private String promptSistema;

    private String promptUsuario;

    private String textoExtraido;

    private String promptFinal;

    private String respuestaIA;

    private LocalDateTime fechaCreacion;

    private List<ArchivoPrompt> archivos;

}