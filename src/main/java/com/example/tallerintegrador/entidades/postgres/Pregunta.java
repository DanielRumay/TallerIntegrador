package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;

@Entity
@Table(name = "pregunta")
public class Pregunta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String Pregunta;

    private Tipo tipodepregunta;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPregunta() {
        return Pregunta;
    }
    public void setPregunta(String pregunta) {}
    public Tipo getTipodepregunta() {
        return tipodepregunta;
    }
    public void setTipodepregunta(Tipo tipodepregunta) {}
}
