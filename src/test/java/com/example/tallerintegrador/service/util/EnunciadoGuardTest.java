package com.example.tallerintegrador.service.util;
import com.example.tallerintegrador.service.ia.PromptTemplateService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El síntoma reportado en producción — reactivos del tipo "en base al punto 3, ¿a qué se
 * refiere?" — ocurre porque la prohibición de referencias estructurales solo vivía como
 * instrucción de prompt (SYSTEM_PROMPT en PromptTemplateService), y una instrucción de
 * prompt es una petición probabilística, no una garantía. Estas pruebas fijan el
 * comportamiento determinista que la reemplaza: EnunciadoGuard no le pide nada al modelo,
 * detecta el patrón léxico y siempre da el mismo veredicto para el mismo texto.
 */
class EnunciadoGuardTest {

    @ParameterizedTest(name = "Debe rechazar: {0}")
    @DisplayName("Detecta las variantes de referencia estructural reportadas y sus equivalentes")
    @ValueSource(strings = {
            "En base al punto 3, ¿a qué se refiere el autor?",
            "Según la sección 2, ¿cuál es la causa principal del fenómeno?",
            "Como se menciona en el párrafo 5, explica la consecuencia.",
            "De acuerdo al punto 4, identifica el elemento central.",
            "Conforme al apartado 2, ¿qué se propone?",
            "En la página 3 se describe un proceso. ¿Cuál es?",
            "El texto anterior plantea una hipótesis. ¿Cuál es?",
            "Punto 2: ¿cuál es la idea principal?",
            "SEGÚN LA SECCIÓN 1, ¿qué se afirma sobre el tema?"
    })
    void rechazaReferenciasEstructurales(String enunciado) {
        assertTrue(EnunciadoGuard.contieneReferenciaEstructural(enunciado),
                "Debía detectar la referencia estructural en: " + enunciado);
    }

    @ParameterizedTest(name = "Debe aceptar: {0}")
    @DisplayName("No genera falsos positivos sobre enunciados autónomos y legítimos")
    @ValueSource(strings = {
            "¿Cuál es la función del sujeto en una oración?",
            "Explica por qué la fotosíntesis requiere luz solar.",
            "El punto de fusión del hielo es 0°C. ¿Qué proceso ocurre al superarlo?",
            "Un párrafo bien estructurado tiene idea principal y de apoyo. Da un ejemplo.",
            "¿Qué diferencia hay entre un sustantivo propio y uno común?",
            "En la sección de un texto argumentativo dedicada a las conclusiones, ¿qué función cumple normalmente?"
    })
    void aceptaEnunciadosAutonomos(String enunciado) {
        assertFalse(EnunciadoGuard.contieneReferenciaEstructural(enunciado),
                "No debía marcar como sospechoso: " + enunciado);
    }

    @Test
    @DisplayName("Es tolerante a entradas nulas o vacías")
    void toleraEntradasVacias() {
        assertFalse(EnunciadoGuard.contieneReferenciaEstructural(null));
        assertFalse(EnunciadoGuard.contieneReferenciaEstructural(""));
        assertFalse(EnunciadoGuard.contieneReferenciaEstructural("   "));
    }
}
