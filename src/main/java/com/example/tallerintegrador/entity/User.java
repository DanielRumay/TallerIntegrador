package com.techcommunityperu.techcommunityperu.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;


@Data
@Entity
@ToString(exclude = {"participante", "ponente"})
@Table(name = "usuario")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "correo_electronico", nullable = false, unique = true)
    private String correoElectronico;

    @Column(name = "contrasenia", nullable = true)
    private String contrasenia;

    @OneToOne(mappedBy = "usuarioId", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private Participante participante;

    @OneToOne(mappedBy = "usuarioId", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private Ponente ponente;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "roles_id", referencedColumnName = "id")
    private Roles roles;

}