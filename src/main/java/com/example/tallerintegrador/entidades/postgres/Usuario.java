package com.example.tallerintegrador.entidades.postgres;

import jakarta.persistence.*;
import lombok.*;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "usuario")
/*
 * Red de seguridad del aislamiento entre colegios: cuando la petición viene de un usuario con
 * colegio, FiltroColegioInterceptor activa este filtro y TODA consulta de Usuario o Curso que
 * pase por JPQL (findAll, findByRol, búsquedas...) queda restringida a ese colegio, aunque el
 * código que la escribió lo haya olvidado. No cubre findById ni consultas nativas: esas se
 * verifican con ColegioGuard.
 */
@org.hibernate.annotations.FilterDef(name = Usuario.FILTRO_COLEGIO,
        parameters = @org.hibernate.annotations.ParamDef(name = "colegioId", type = Long.class))
@org.hibernate.annotations.Filter(name = Usuario.FILTRO_COLEGIO, condition = "colegio_id = :colegioId")
public class Usuario {

    public static final String FILTRO_COLEGIO = "filtroColegio";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nombre;

    @Column(unique = true)
    private String correo;

    private String password;

    @Enumerated(EnumType.STRING)
    private Rol rol;

    @ManyToOne
    @JoinColumn(name = "grado_id")
    private Grado grado;

    @ManyToOne
    @JoinColumn(name = "seccion_id")
    private Seccion seccion;

   @Enumerated(EnumType.STRING)
   private NivelConocimiento nivelConocimiento;
   
   @Column(name = "dificultades_detectadas", columnDefinition = "TEXT")
   private String dificultadesDetectadas;

   @Column(name = "diagnostico_completado", columnDefinition = "BOOLEAN DEFAULT FALSE", nullable = false)
   private boolean diagnosticoCompletado = false;

   @Column(name = "consentimiento_aceptado", columnDefinition = "BOOLEAN DEFAULT FALSE", nullable = false)
   private boolean consentimientoAceptado = false;

   @Column(name = "fecha_aceptacion_consentimiento")
   private java.time.LocalDateTime fechaAceptacionConsentimiento;

   @Column(name = "version_politica_aceptada")
   private String versionPoliticaAceptada;

   @Column(name = "requires_password_setup", columnDefinition = "BOOLEAN DEFAULT FALSE", nullable = false)
   private boolean requiresPasswordSetup = false;

   @Column(name = "intentos_fallidos", columnDefinition = "INTEGER DEFAULT 0", nullable = false)
   private int intentosFallidos = 0;

   @Column(name = "cuenta_bloqueada", columnDefinition = "BOOLEAN DEFAULT FALSE", nullable = false)
   private boolean cuentaBloqueada = false;

   /** Colegio al que pertenece. NULL solo para el SUPERADMIN. @JsonIgnore: nunca se serializa la entidad. */
   @com.fasterxml.jackson.annotation.JsonIgnore
   @ManyToOne(fetch = FetchType.LAZY)
   @JoinColumn(name = "colegio_id")
   private Colegio colegio;

   /** Quién creó la cuenta (trazabilidad). Id y no relación: el registro sobrevive al creador. */
   @Column(name = "creado_por_id")
   private Long creadoPorId;

   /**
    * Administrador principal del colegio: tiene todos los permisos y solo el SUPERADMIN puede
    * quitárselos. Un colegio siempre conserva al menos uno activo.
    */
   @Column(name = "admin_principal", columnDefinition = "BOOLEAN DEFAULT FALSE", nullable = false)
   private boolean adminPrincipal = false;

   /** Permisos de administración (solo para rol ADMIN). Ver Permiso. */
   @ElementCollection(fetch = FetchType.EAGER)
   @CollectionTable(name = "usuario_permiso", joinColumns = @JoinColumn(name = "usuario_id"))
   @Enumerated(EnumType.STRING)
   @Column(name = "permiso", length = 40, nullable = false)
   private java.util.Set<Permiso> permisos = new java.util.HashSet<>();

   /** Id del colegio sin inicializar el proxy perezoso. */
   public Long colegioId() {
       return colegio == null ? null : colegio.getId();
   }}
