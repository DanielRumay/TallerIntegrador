package com.example.tallerintegrador.service.metricas;

import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class MetricasEstandarizadasService {

    //Calcula el Índice de Lecturabilidad de Fernández Huerta.
    //90-100: Muy fácil | 60-70: Normal | 0-30: Muy difícil / Confuso
    public double calcularLecturabilidad(String texto) {
        if (texto == null || texto.trim().isEmpty()) return 0.0;

        // .trim() evita que espacios al inicio/final cuenten como palabras vacías
        String[] frases = texto.trim().split("[.!?]+");
        String[] palabras = texto.trim().split("\\s+");

        int numFrases = Math.max(1, frases.length);
        int numPalabras = Math.max(1, palabras.length);
        int numSilabas = contarSilabasAproximadas(texto);

        double indice = 206.84 - (60.0 * numSilabas / numPalabras) - (1.02 * numPalabras / numFrases);

        // Aseguramos que el índice no se salga del rango 0-100
        return Math.max(0, Math.min(100, Math.round(indice * 100.0) / 100.0));
    }

    /**
     * Índice de perspicuidad de Flesch-Szigriszt (1993), la adaptación al español de referencia.
     *
     * POR QUE ADEMAS DE FERNANDEZ HUERTA. La fórmula de Fernández Huerta (1959) arrastra dos
     * problemas conocidos: una inconsistencia lógica —al aumentar el número de frases en una
     * muestra de 100 palabras la puntuación baja, cuando frases más cortas deberían facilitar la
     * lectura— y la ausencia de un procedimiento de validación publicado. Szigriszt-Pazos sí fue
     * validada, y su escala de interpretación (INFLESZ) se reajustó con 210 textos por
     * Barrio-Cantalejo et al. (2008).
     *
     * Se conservan las dos: Szigriszt como indicador de referencia y Fernández Huerta para no
     * perder la comparación con lo ya medido en la fase 1.
     *
     * LIMITE QUE NINGUNA RESUELVE. Ambas se diseñaron para textos de 100 palabras o más. Sobre
     * un enunciado de veinte, una sola palabra larga mueve el índice varios puntos. Por eso el
     * indicador principal del nivel de lectura es la estimación del crítico pedagógico, validada
     * contra el juicio del docente, y estas fórmulas se usan como referencia agregada.
     */
    public double calcularPerspicuidadSzigriszt(String texto) {
        if (texto == null || texto.trim().isEmpty()) return 0.0;

        String[] frases = texto.trim().split("[.!?]+");
        String[] palabras = texto.trim().split("\\s+");

        int numFrases = Math.max(1, frases.length);
        int numPalabras = Math.max(1, palabras.length);
        int numSilabas = contarSilabasAproximadas(texto);

        double indice = 206.835 - (62.3 * numSilabas / numPalabras) - ((double) numPalabras / numFrases);

        return Math.max(0, Math.min(100, Math.round(indice * 100.0) / 100.0));
    }

    /**
     * Grado de dificultad según la escala INFLESZ, aplicada sobre el índice de Szigriszt.
     *
     * Los cinco tramos son los de Barrio-Cantalejo et al. (2008): muy difícil (< 40), algo
     * difícil (40-55), normal (55-65), bastante fácil (65-80) y muy fácil (> 80).
     */
    public String nivelInflesz(double indiceSzigriszt) {
        if (indiceSzigriszt < 40) return "muy difícil";
        if (indiceSzigriszt < 55) return "algo difícil";
        if (indiceSzigriszt < 65) return "normal";
        if (indiceSzigriszt < 80) return "bastante fácil";
        return "muy fácil";
    }

    //Heurística para estimar sílabas en español basada en vocales.
    private int contarSilabasAproximadas(String texto) {
        String vocales = "aeiouáéíóúüAEIOUÁÉÍÓÚÜ";
        int silabas = 0;
        boolean anteriorEraVocal = false;

        for (char c : texto.toCharArray()) {
            if (vocales.indexOf(c) >= 0) {
                if (!anteriorEraVocal) {
                    silabas++;
                    anteriorEraVocal = true;
                }
            } else {
                anteriorEraVocal = false;
            }
        }
        return Math.max(1, silabas);
    }

    //Calcula el TTR (Type-Token Ratio) para medir la riqueza léxica.
    // Rango: 0.0 a 1.0 (Más cerca a 1.0 = Vocabulario más rico y menos repetitivo)
    public double calcularTTR(String texto) {
        if (texto == null || texto.trim().isEmpty()) return 0.0;

        //limpieza a minúsculas y quitamos todo lo que no sea letra o espacio
        String textoLimpio = texto.trim().toLowerCase().replaceAll("[^a-záéíóúüñ\\s]", "");

        // Si después de limpiar se quedó vacío (ej. si solo eran números), salimos
        if (textoLimpio.isEmpty()) return 0.0;

        String[] tokens = textoLimpio.split("\\s+");
        if (tokens.length == 0 || tokens[0].isEmpty()) return 0.0;

        // Usamos un HashSet para dejar solo las palabras únicas
        Set<String> types = new HashSet<>(Arrays.asList(tokens));

        // TTR = Types (Únicas) / Tokens (Totales)
        double ttr = (double) types.size() / tokens.length;

        return Math.round(ttr * 100.0) / 100.0;
    }

    //Calcula la Similitud de Coseno entre dos vectores de Embeddings.
    //Rango: -1.0 a 1.0 (Más cerca a 1.0 = Más relevancia semántica con el texto origen)
    public double calcularSimilitudCoseno(List<Float> vectorA, List<Float> vectorB) {
        if (vectorA == null || vectorB == null || vectorA.isEmpty() || vectorA.size() != vectorB.size()) {
            return 0.0;
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < vectorA.size(); i++) {
            dotProduct += vectorA.get(i) * vectorB.get(i);
            normA += Math.pow(vectorA.get(i), 2);
            normB += Math.pow(vectorB.get(i), 2);
        }

        // Evitar división por cero si envían un vector vacío o lleno de ceros
        if (normA == 0.0 || normB == 0.0) return 0.0;

        double similitud = dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
        return Math.round(similitud * 1000.0) / 1000.0; // Redondeado a 3 decimales
    }
}