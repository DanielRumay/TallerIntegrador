package com.example.tallerintegrador.entidades.postgres;

import com.example.tallerintegrador.entidades.mongodb.ArchivoPrompt;
import jakarta.persistence.*;

import java.util.List;

@Entity
@Table(name = "semana")
public class Semana {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String numSem;

    @OneToMany(mappedBy = "semana")
    private List<Usuario> usuarios;

    private String Nombre_PDF;

    private String Informacion_PDF;

    @OneToMany(mappedBy = "semana")
    private List<Pregunta> preguntas;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNumSem() {
        return numSem;
    }

    public void setNumSem(String numSem) {
        this.numSem = numSem;
    }

    public List<Usuario> getUsuarios() {
        return usuarios;
    }

    public void setUsuarios(List<Usuario> usuarios) {
        this.usuarios = usuarios;
    }

    public String getNombre_PDF() {
        return Nombre_PDF;
    }

    public void setNombre_PDF(String nombre_PDF) {
        Nombre_PDF = nombre_PDF;
    }

    public String getInformacion_PDF() {
        return Informacion_PDF;
    }

    public void setInformacion_PDF(String informacion_PDF) {
        Informacion_PDF = informacion_PDF;
    }

    public List<Pregunta> getPreguntas() {
        return preguntas;
    }

    public void setPreguntas(List<Pregunta> preguntas) {
        this.preguntas = preguntas;
    }
}