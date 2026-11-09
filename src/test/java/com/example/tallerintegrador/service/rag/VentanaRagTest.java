package com.example.tallerintegrador.service.rag;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VentanaRagTest {

    private final List<Integer> ranking = IntStream.range(0, 20).boxed().toList();

    @Test
    void cadaVueltaLeeOtraParteHastaCubrirTodo() {
        Set<Integer> vistos = new HashSet<>();
        for (int vuelta = 0; vuelta < 3; vuelta++) {
            vistos.addAll(RagRetrieverService.ventana(ranking, vuelta, 8));
        }
        assertEquals(20, vistos.size());
    }

    @Test
    void siempreEntregaElMismoTamano() {
        assertEquals(8, RagRetrieverService.ventana(ranking, 2, 8).size());
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), RagRetrieverService.ventana(ranking, 3, 8));
    }

    @Test
    void siHayPocosFragmentosLosUsaTodos() {
        assertEquals(List.of(1, 2), RagRetrieverService.ventana(List.of(1, 2), 5, 8));
    }
}
