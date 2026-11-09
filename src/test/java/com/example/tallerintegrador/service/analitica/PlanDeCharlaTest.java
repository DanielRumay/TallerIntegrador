package com.example.tallerintegrador.service.analitica;

import com.example.tallerintegrador.service.util.VocabularioFrecuente;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PlanDeCharlaTest {

    private static final Double[] DOMINIOS = {null, 0.2, 0.6, 0.9};

    @RepeatedTest(40)
    void todoPlanCumpleLasReglas() {
        for (boolean primaria : new boolean[]{true, false}) {
            for (Double dominio : DOMINIOS) {
                List<PlanDeCharla.Paso> plan = PlanDeCharla.armar(primaria, dominio, new Random());
                assertEquals(PlanDeCharla.TOTAL, plan.size());
                assertTrue(plan.stream().distinct().count() <= 3, "máximo tres llamadas al generador");
                assertTrue(plan.stream().map(PlanDeCharla.Paso::tipo).distinct().count() >= 2, "debe haber variedad");
                assertTrue(PlanDeCharla.maximoSeguidos(plan) <= 2, "no más de dos seguidas: " + plan);
                assertTrue(plan.stream().anyMatch(p -> !p.bloom().equals("Comprender")), "nadie se queda solo en lo básico");
            }
        }
    }

    @RepeatedTest(20)
    void primariaSoloTocaOpciones() {
        for (Double dominio : DOMINIOS) {
            assertTrue(PlanDeCharla.armar(true, dominio, new Random()).stream()
                    .allMatch(p -> p.tipo().equals(PlanDeCharla.OM) || p.tipo().equals(PlanDeCharla.VF)));
        }
    }

    @Test
    void elDesafioDeSecundariaTerminaArgumentando() {
        List<PlanDeCharla.Paso> plan = PlanDeCharla.desafio(false);
        assertEquals(PlanDeCharla.AB, plan.get(plan.size() - 1).tipo());
        assertEquals("Evaluar", plan.get(plan.size() - 1).bloom());
    }

    @Test
    void ariaNoPuedeRevelarLaRespuestaNiExtenderse() {
        assertFalse(AriaCharlaService.aceptable("La respuesta es la fotosintesis, claro.", "A) fotosíntesis", 30));
        assertFalse(AriaCharlaService.aceptable(String.join(" ", Stream.generate(() -> "mira").limit(40).toList()), "x", 30));
        assertFalse(AriaCharlaService.aceptable("{\"preguntas\":[]}", "x", 30));
        assertTrue(AriaCharlaService.aceptable("¿Quién hace la acción?", "El perro", 30));
    }

    @Test
    void elVocabularioSeAjustaALaEdadYRespetaElMaterial() {
        VocabularioFrecuente v = new VocabularioFrecuente();
        String texto = "Determina la idiosincrasia del perro";
        assertTrue(v.rarasPara(texto, "III", "").contains("idiosincrasia"));
        assertFalse(v.rarasPara(texto, "III", "La idiosincrasia del pueblo").contains("idiosincrasia"));
        assertTrue(v.rarasPara(texto, null, "").isEmpty());
        assertTrue(v.rarasPara("El perro corre por la calle", "III", "").isEmpty());
    }
}
