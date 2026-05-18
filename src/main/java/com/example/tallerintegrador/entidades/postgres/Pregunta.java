package com.example.tallerintegrador.entidades.postgres;

import com.example.tallerintegrador.entidades.postgres.Semana;
import java.util.List;
import jakarta.persistence.*;

@Entity
@Table(name = "pregunta")
public class Pregunta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String Pregunta;


    private Tipo tipodepregunta;

    @ManyToOne
    @JoinColumn(name = "semana_id")
    private Semana semana;

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
