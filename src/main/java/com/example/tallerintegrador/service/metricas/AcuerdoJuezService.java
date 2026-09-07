package com.example.tallerintegrador.service.metricas;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Concordancia entre la calificación del juez de IA y la de un docente humano.
 *
 * Para qué sirve: sin este número, la afirmación "el sistema califica como un profesor" es
 * una opinión. Con él, es un dato reportable. Es la pieza que sostiene la validez del
 * constructo frente a un jurado.
 *
 * Por qué kappa y no el porcentaje de coincidencias: si el 80 % de las respuestas son
 * correctas, dos calificadores que siempre dijeran "correcto" coincidirían el 80 % de las
 * veces sin haber leído nada. Kappa descuenta el acuerdo esperado por azar; el porcentaje
 * crudo, no. Se reporta igualmente el porcentaje porque es fácil de leer, pero la cifra que
 * se defiende es kappa.
 *
 * Se usa kappa PONDERADA CUADRÁTICA porque la escala es ordinal: en una escala de 1 a 4,
 * confundir un 3 con un 4 es un desacuerdo menor que confundir un 1 con un 4, y la kappa sin
 * ponderar trataría ambos casos como el mismo error. Se reporta también la versión sin
 * ponderar para poder comparar con literatura que use esa convención.
 *
 * Referencias prácticas de lectura habituales en la literatura: kappa > 0,6 se considera
 * acuerdo sustancial y > 0,8 casi perfecto. No son umbrales normativos; son convenciones.
 */
@Slf4j
@Service
public class AcuerdoJuezService {

    /** Una respuesta calificada por los dos: la IA y un docente. */
    public record ParCalificacion(int ia, int docente) {}

    public record ResultadoAcuerdo(
            int n,
            int escalaMin,
            int escalaMax,
            double acuerdoExacto,
            double acuerdoAdyacente,
            double kappaCuadratica,
            double kappaSinPonderar,
            double sesgoMedio,
            String interpretacion
    ) {}

    /**
     * @param pares     calificaciones emparejadas (IA, docente) de las mismas respuestas
     * @param escalaMin valor mínimo de la escala (ej. 1)
     * @param escalaMax valor máximo de la escala (ej. 4)
     */
    public ResultadoAcuerdo calcular(List<ParCalificacion> pares, int escalaMin, int escalaMax) {
        if (pares == null || pares.isEmpty()) {
            return new ResultadoAcuerdo(0, escalaMin, escalaMax, 0, 0, 0, 0, 0,
                    "Sin datos: ningún docente ha calificado todavía respuestas de la muestra.");
        }
        if (escalaMax < escalaMin) {
            throw new IllegalArgumentException("La escala máxima no puede ser menor que la mínima");
        }

        int k = escalaMax - escalaMin + 1;
        int n = pares.size();

        // Matriz de confusión: filas = calificación de la IA, columnas = la del docente.
        double[][] observada = new double[k][k];
        double[] margenIa = new double[k];
        double[] margenDocente = new double[k];

        int exactos = 0;
        int adyacentes = 0;
        double sumaDiferencias = 0;

        for (ParCalificacion par : pares) {
            int i = indice(par.ia(), escalaMin, escalaMax);
            int j = indice(par.docente(), escalaMin, escalaMax);
            observada[i][j] += 1;
            margenIa[i] += 1;
            margenDocente[j] += 1;

            int diferencia = par.ia() - par.docente();
            sumaDiferencias += diferencia;
            if (diferencia == 0) exactos++;
            if (Math.abs(diferencia) <= 1) adyacentes++;
        }

        double acuerdoExacto = (double) exactos / n;
        double acuerdoAdyacente = (double) adyacentes / n;
        double sesgoMedio = sumaDiferencias / n;

        double kappaCuadratica = kappaPonderada(observada, margenIa, margenDocente, n, k);
        double kappaSinPonderar = kappaSimple(observada, margenIa, margenDocente, n, k);

        return new ResultadoAcuerdo(
                n, escalaMin, escalaMax,
                acuerdoExacto, acuerdoAdyacente,
                kappaCuadratica, kappaSinPonderar, sesgoMedio,
                interpretar(kappaCuadratica, sesgoMedio, n));
    }

    /** Recorta al rango declarado en vez de fallar: una nota fuera de escala no debe tumbar el cálculo. */
    private int indice(int valor, int escalaMin, int escalaMax) {
        int acotado = Math.max(escalaMin, Math.min(escalaMax, valor));
        if (acotado != valor) {
            log.warn("[ACUERDO] Calificación {} fuera de la escala [{}, {}]; se acota a {}",
                    valor, escalaMin, escalaMax, acotado);
        }
        return acotado - escalaMin;
    }

    private double kappaPonderada(double[][] observada, double[] margenIa, double[] margenDocente, int n, int k) {
        // Con una sola categoría posible no hay desacuerdo representable: el acuerdo es total
        // por construcción, y la fórmula dividiría por cero.
        if (k < 2) return 1.0;

        double denominadorPeso = Math.pow(k - 1, 2);
        double numerador = 0;
        double denominador = 0;

        for (int i = 0; i < k; i++) {
            for (int j = 0; j < k; j++) {
                double peso = Math.pow(i - j, 2) / denominadorPeso;
                double esperada = (margenIa[i] * margenDocente[j]) / n;
                numerador += peso * observada[i][j];
                denominador += peso * esperada;
            }
        }

        // Ocurre cuando ambos calificadores usaron una sola categoría (todo 4, por ejemplo):
        // no hay variación, así que no hay nada que el azar pudiera explicar.
        if (denominador == 0) {
            return numerador == 0 ? 1.0 : 0.0;
        }
        return 1 - (numerador / denominador);
    }

    private double kappaSimple(double[][] observada, double[] margenIa, double[] margenDocente, int n, int k) {
        double po = 0;
        double pe = 0;
        for (int i = 0; i < k; i++) {
            po += observada[i][i];
            pe += (margenIa[i] * margenDocente[i]);
        }
        po = po / n;
        pe = pe / ((double) n * n);

        if (pe == 1.0) {
            return po == 1.0 ? 1.0 : 0.0;
        }
        return (po - pe) / (1 - pe);
    }

    /**
     * Texto de lectura. Dice explícitamente qué NO demuestra el número, siguiendo el mismo
     * criterio que MetricasIAService: una métrica sin su límite declarado se sobreinterpreta.
     */
    private String interpretar(double kappa, double sesgo, int n) {
        String nivel;
        if (kappa < 0.20) nivel = "acuerdo escaso";
        else if (kappa < 0.40) nivel = "acuerdo aceptable";
        else if (kappa < 0.60) nivel = "acuerdo moderado";
        else if (kappa < 0.80) nivel = "acuerdo sustancial";
        else nivel = "acuerdo casi perfecto";

        String direccion;
        if (Math.abs(sesgo) < 0.10) direccion = "sin sesgo apreciable entre la IA y el docente";
        else if (sesgo > 0) direccion = "la IA califica por encima del docente en promedio";
        else direccion = "la IA califica por debajo del docente en promedio";

        String aviso = n < 50
                ? " ATENCIÓN: con solo " + n + " respuestas calificadas la estimación es inestable;"
                  + " conviene llegar al menos a 50 antes de reportar esta cifra."
                : "";

        return "Kappa ponderada cuadrática = " + String.format("%.3f", kappa) + " (" + nivel + "), "
                + direccion + ". Mide la coincidencia entre el juez de IA y un docente humano sobre"
                + " las mismas respuestas; NO demuestra que la calificación sea pedagógicamente"
                + " correcta, solo que ambos calificadores concuerdan." + aviso;
    }
}
