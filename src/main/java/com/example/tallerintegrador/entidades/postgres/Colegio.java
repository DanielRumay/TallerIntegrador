package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Una institución educativa. Todo usuario (salvo el SUPERADMIN) y todo curso pertenecen a uno.
 *
 * POR QUE EXISTE. Una sola instalación atiende a varios colegios, y el administrador de uno no
 * debe ver ni tocar los datos de otro. El resto de tablas (semanas, intentos, matrículas...)
 * cuelgan de Curso o de Usuario y heredan el colegio, así que no se repite la columna.
 *
 * Un colegio desactivado no se borra: sus usuarios dejan de poder entrar, pero los datos se
 * conservan por si hay que reactivarlo o atender una solicitud de la institución.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "colegio")
public class Colegio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 150)
    private String nombre;

    @Column(nullable = false, columnDefinition = "BOOLEAN DEFAULT TRUE")
    private boolean activo = true;

    @Column(name = "creado_en", nullable = false)
    private LocalDateTime creadoEn = LocalDateTime.now();

    public Colegio(String nombre) {
        this.nombre = nombre;
    }
}
