package com.example.tallerintegrador.service.analitica;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Decide qué tipo de pregunta y qué nivel lleva cada momento de una charla con Aria.
 *
 * Aria "elige" ante el alumno, pero la decisión es esta regla: no gasta llamadas a la IA y el
 * docente puede saber por qué salió cada pregunta. El tipo depende de la exigencia que toca, no
 * de un formato favorito del alumno. El azar solo actúa entre los tipos que corresponden.
 *
 * Cada plan tiene como máximo tres combinaciones de tipo y nivel, porque cada combinación es una
 * llamada al generador. Todos los planes incluyen preguntas por encima de lo más básico: un alumno
 * al que le va mal no debe quedarse sin practicar lo que le exige su grado.
 */
public final class PlanDeCharla {

    private PlanDeCharla() {}

    public static final String OM = "OPCION_MULTIPLE";
    public static final String VF = "VERDADERO_FALSO";
    public static final String DE = "DETECCION_ERRORES";
    public static final String AB = "ABIERTA";

    public static final int TOTAL = 5;
    static final double DOMINIO_BAJO = 0.45;
    static final double DOMINIO_ALTO = 0.80;

    private static final List<String> ORDEN_BLOOM = List.of("Comprender", "Aplicar", "Analizar", "Evaluar");

    public record Paso(String tipo, String bloom) {}

    private record Fila(String bloom, int cantidad, List<String> tipos) {}

    /** @param dominio dominio medio del alumno en la semana, de 0 a 1; null si aún no hay datos */
    public static List<Paso> armar(boolean primaria, Double dominio, Random azar) {
        List<Fila> filas = primaria ? filasPrimaria(dominio) : filasSecundaria(dominio);
        List<Paso> pasos = sortear(filas, azar);
        for (int i = 0; i < 80 && !variado(pasos); i++) pasos = sortear(filas, azar);
        return ordenar(pasos, azar);
    }

    /** Cierre de la semana: en secundaria termina enseñándole a Aria o defendiendo una idea. */
    public static List<Paso> desafio(boolean primaria) {
        return primaria
                ? List.of(new Paso(VF, "Aplicar"), new Paso(OM, "Aplicar"), new Paso(OM, "Analizar"))
                : List.of(new Paso(OM, "Analizar"), new Paso(DE, "Analizar"), new Paso(AB, "Evaluar"));
    }

    private static List<Fila> filasPrimaria(Double d) {
        if (d == null) return List.of(new Fila("Comprender", 3, List.of(OM, VF)), new Fila("Aplicar", 2, List.of(OM, VF)));
        if (d < DOMINIO_BAJO) return List.of(new Fila("Comprender", 3, List.of(OM, VF)), new Fila("Aplicar", 2, List.of(OM)));
        if (d < DOMINIO_ALTO) return List.of(new Fila("Comprender", 2, List.of(VF)), new Fila("Aplicar", 2, List.of(OM)), new Fila("Analizar", 1, List.of(OM)));
        return List.of(new Fila("Aplicar", 2, List.of(VF)), new Fila("Analizar", 3, List.of(OM)));
    }

    private static List<Fila> filasSecundaria(Double d) {
        if (d == null) return List.of(new Fila("Comprender", 2, List.of(VF, OM)), new Fila("Aplicar", 2, List.of(OM)), new Fila("Analizar", 1, List.of(DE, AB)));
        if (d < DOMINIO_BAJO) return List.of(new Fila("Comprender", 2, List.of(VF, OM)), new Fila("Aplicar", 2, List.of(OM)), new Fila("Analizar", 1, List.of(OM, DE)));
        if (d < DOMINIO_ALTO) return List.of(new Fila("Aplicar", 2, List.of(VF, OM)), new Fila("Analizar", 2, List.of(DE, OM)), new Fila("Evaluar", 1, List.of(AB)));
        return List.of(new Fila("Analizar", 2, List.of(DE, OM)), new Fila("Evaluar", 3, List.of(AB, OM)));
    }

    private static List<Paso> sortear(List<Fila> filas, Random azar) {
        List<Paso> pasos = new ArrayList<>();
        for (Fila f : filas) {
            String tipo = f.tipos().get(azar.nextInt(f.tipos().size()));
            for (int i = 0; i < f.cantidad(); i++) pasos.add(new Paso(tipo, f.bloom()));
        }
        return pasos;
    }

    /** Con tres o menos del mismo tipo siempre se puede ordenar sin más de dos seguidas. */
    private static boolean variado(List<Paso> pasos) {
        var cuentas = pasos.stream().collect(java.util.stream.Collectors.groupingBy(Paso::tipo, java.util.stream.Collectors.counting()));
        return cuentas.size() >= 2 && cuentas.values().stream().allMatch(n -> n <= 3);
    }

    /** Empieza por lo más sencillo y evita repetir el mismo tipo más de dos veces seguidas. */
    static List<Paso> ordenar(List<Paso> pasos, Random azar) {
        int minimo = pasos.stream().mapToInt(p -> ORDEN_BLOOM.indexOf(p.bloom())).min().orElse(0);
        List<Paso> mejor = new ArrayList<>(pasos);
        for (int i = 0; i < 200; i++) {
            List<Paso> prueba = new ArrayList<>(pasos);
            java.util.Collections.shuffle(prueba, azar);
            boolean empiezaFacil = ORDEN_BLOOM.indexOf(prueba.get(0).bloom()) == minimo;
            if (empiezaFacil && maximoSeguidos(prueba) <= 2) return prueba;
            if (empiezaFacil) mejor = prueba;
        }
        return mejor;
    }

    static int maximoSeguidos(List<Paso> pasos) {
        int mayor = 0, racha = 0;
        String anterior = null;
        for (Paso p : pasos) {
            racha = p.tipo().equals(anterior) ? racha + 1 : 1;
            mayor = Math.max(mayor, racha);
            anterior = p.tipo();
        }
        return mayor;
    }
}
