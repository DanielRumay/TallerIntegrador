package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;
import lombok.Data;
import java.util.List;

@Data
@Entity
@Table(name = "fragmentos_pdf")
public class FragmentoPDF {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nombre_archivo")
    private String nombreArchivo;

    // Usamos columnDefinition = "TEXT" porque los fragmentos pueden ser largos
    @Column(columnDefinition = "TEXT")
    private String texto;

    // AQUÍ ESTÁ LA MAGIA DE PGVECTOR
    // Dependiendo de cómo configures Hibernate/pgvector, suele mapearse a un array o List
    // El modelo text-embedding-004 de Gemini devuelve un vector de 768 dimensiones
    @Column(columnDefinition = "vector(768)")
    private List<Float> embedding;
}