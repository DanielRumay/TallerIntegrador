package com.example.tallerintegrador.entidades.mongodb;

import com.example.tallerintegrador.entidades.postgres.Usuario;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Document(collection = "prompts")
public class Prompt {

    @Id
    private String id;

    private Usuario usuarioId;

    private String promptSistema;

    private String promptUsuario;

    private String textoExtraido;

    private String promptFinal;

    private String respuestaIA;

    private LocalDateTime fechaCreacion;

    private List<ArchivoPrompt> archivos;

    public String getId() {
        return id;
    }

    public String getPromptSistema() {
        return promptSistema;
    }

    public void setPromptSistema(String promptSistema) {
        this.promptSistema = promptSistema;
    }

    public String getPromptUsuario() {
        return promptUsuario;
    }

    public void setPromptUsuario(String promptUsuario) {
        this.promptUsuario = promptUsuario;
    }

    public String getTextoExtraido() {
        return textoExtraido;
    }

    public void setTextoExtraido(String textoExtraido) {
        this.textoExtraido = textoExtraido;
    }

    public String getPromptFinal() {
        return promptFinal;
    }

    public void setPromptFinal(String promptFinal) {
        this.promptFinal = promptFinal;
    }

    public String getRespuestaIA() {
        return respuestaIA;
    }

    public void setRespuestaIA(String respuestaIA) {
        this.respuestaIA = respuestaIA;
    }

    public LocalDateTime getFechaCreacion() {
        return fechaCreacion;
    }

    public void setFechaCreacion(LocalDateTime fechaCreacion) {
        this.fechaCreacion = fechaCreacion;
    }

    public List<ArchivoPrompt> getArchivos() {
        return archivos;
    }

    public void setArchivos(List<ArchivoPrompt> archivos) {
        this.archivos = archivos;
    }
}