package com.example.tallerintegrador.service.analitica;

import com.example.tallerintegrador.entidades.postgres.Curso;
import com.example.tallerintegrador.entidades.postgres.DominioConceptoAlumno;
import com.example.tallerintegrador.repository.DominioConceptoAlumnoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Mapa de conocimiento concepto × nivel de Bloom, agrupado por curso.
 *
 * El mapa de calor original (RendimientoService.obtenerMapaCalor) agrega por curso/semana
 * porque es lo único que el esquema anterior permitía: Pregunta no guardaba concepto ni
 * nivel de Bloom. Este servicio lee dominio_concepto_alumno (ver ConocimientoBktService) y
 * expone, por curso, cada tema con su probabilidad de dominio real — no un % de aciertos
 * crudo — para que el alumno vea explícitamente qué tema puntual le falta entender, no solo
 * "esta semana te fue regular".
 *
 * "intensidadCalor" = 1 − probabilidadDominio: es el eje visual pensado para el frontend
 * (mismo lenguaje que un mapa de calor real — cuanto más caliente/rojo, más urge repasar
 * ese tema, igual que en un mapa de densidad de incidentes un punto caliente es "aquí hay
 * más problema", no "aquí hay más de lo bueno").
 *
 * Endpoint nuevo y aditivo (GET /rendimiento/mapa-conocimiento/{usuarioId}): no reemplaza
 * /mapa-calor, que el frontend actual sigue consumiendo sin cambios.
 */
@Service
@RequiredArgsConstructor
public class MapaConocimientoService {

    private final DominioConceptoAlumnoRepository repository;

    private static final double UMBRAL_DOMINADO = 0.75;
    private static final double UMBRAL_EN_PROGRESO = 0.50;
    private static final int OBSERVACIONES_MINIMAS_CONFIABLES = 3;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> obtenerMapaDeConocimiento(Long usuarioId) {
        List<DominioConceptoAlumno> registros = repository.findByUsuarioIdOrderByConceptoAsc(usuarioId);

        Map<String, List<DominioConceptoAlumno>> porCurso = registros.stream()
                .collect(Collectors.groupingBy(
                        this::claveCurso,
                        LinkedHashMap::new,
                        Collectors.toList()));

        return porCurso.entrySet().stream()
                .map(e -> aGrupoCurso(e.getValue()))
                .sorted(Comparator.comparing(g -> (String) g.get("cursoNombre"),
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private String claveCurso(DominioConceptoAlumno registro) {
        return registro.getCurso() != null ? String.valueOf(registro.getCurso().getId()) : "SIN_CURSO";
    }

    private Map<String, Object> aGrupoCurso(List<DominioConceptoAlumno> registrosDelCurso) {
        Curso curso = registrosDelCurso.get(0).getCurso();

        List<Map<String, Object>> temas = registrosDelCurso.stream()
                .map(this::aTema)
                .sorted(Comparator.comparingDouble(t -> -(double) t.get("intensidadCalor"))) // más urgente primero
                .toList();

        long temasDebiles = temas.stream().filter(t -> "DEBIL".equals(t.get("nivel"))).count();
        OptionalDouble promedioDominio = registrosDelCurso.stream()
                .mapToDouble(DominioConceptoAlumno::getProbabilidadDominio)
                .average();

        Map<String, Object> grupo = new LinkedHashMap<>();
        grupo.put("cursoId", curso != null ? curso.getId() : null);
        grupo.put("cursoNombre", curso != null ? curso.getNombre() : "Sin curso asignado");
        grupo.put("cursoColor", curso != null ? curso.getColor() : "primary");
        grupo.put("cursoEmoji", curso != null ? curso.getEmoji() : "📚");
        grupo.put("totalTemas", temas.size());
        grupo.put("temasDebiles", temasDebiles);
        grupo.put("promedioDominio", promedioDominio.isPresent()
                ? Math.round(promedioDominio.getAsDouble() * 1000.0) / 1000.0 : null);
        grupo.put("temas", temas);
        return grupo;
    }

    private Map<String, Object> aTema(DominioConceptoAlumno registro) {
        double p = registro.getProbabilidadDominio();
        boolean confiable = registro.getObservaciones() >= OBSERVACIONES_MINIMAS_CONFIABLES;

        String nivel;
        if (!confiable) {
            nivel = "DATOS_INSUFICIENTES";
        } else if (p >= UMBRAL_DOMINADO) {
            nivel = "DOMINADO";
        } else if (p >= UMBRAL_EN_PROGRESO) {
            nivel = "EN_PROGRESO";
        } else {
            nivel = "DEBIL";
        }

        Map<String, Object> tema = new LinkedHashMap<>();
        // Se muestra la etiqueta legible; `concepto` es la clave de agrupacion, no un texto
        // para leer. Los registros anteriores a la canonizacion no tienen etiqueta: para
        // esos se recompone una a partir de la clave.
        tema.put("concepto",
                registro.getConceptoEtiqueta() != null && !registro.getConceptoEtiqueta().isBlank()
                        ? registro.getConceptoEtiqueta()
                        : com.example.tallerintegrador.service.util.NormalizadorConcepto
                                .paraMostrar(registro.getConcepto()));
        // Ejes del mapa temporal: sin estos dos, el frontend no puede situar el concepto en
        // una columna de semana y solo puede dibujar una nube sin orden.
        tema.put("semanaId", registro.getSemana() != null ? registro.getSemana().getId() : null);
        tema.put("semanaNumero", registro.getSemana() != null ? registro.getSemana().getNumSem() : null);
        tema.put("semanaTema", registro.getSemana() != null ? registro.getSemana().getNombreTema() : null);
        tema.put("nivelBloom", registro.getNivelBloom());
        tema.put("probabilidadDominio", Math.round(p * 1000.0) / 1000.0);
        tema.put("intensidadCalor", Math.round((1 - p) * 1000.0) / 1000.0);
        tema.put("observaciones", registro.getObservaciones());
        tema.put("confiable", confiable);
        tema.put("nivel", nivel);
        tema.put("actualizadoEn", registro.getFechaActualizacion());
        return tema;
    }
}
