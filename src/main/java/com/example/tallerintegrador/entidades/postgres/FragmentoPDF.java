package com.example.tallerintegrador.entidades.postgres;

import com.example.tallerintegrador.config.VectorConverter;
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

    @Column(columnDefinition = "TEXT")
    private String texto;

    @Column(name = "archivo_mongo_id")
    private String archivoMongoId;
}