package com.example.tallerintegrador.service.analitica;

import com.example.tallerintegrador.entidades.postgres.DominioConceptoAlumno;
import com.example.tallerintegrador.entidades.postgres.Semana;
import com.example.tallerintegrador.repository.DominioConceptoAlumnoRepository;
import com.example.tallerintegrador.service.util.IdHasher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * La API entrega los ids de semana codificados, y el frontend filtra el mapa de la semana
 * comparando contra ese valor. Si el mapa devolviera el id numerico, la vista saldria vacia.
 */
class MapaConocimientoSemanaIdTest {

    @Test
    @DisplayName("El tema trae el id de semana codificado, igual que el resto de la API")
    void semanaIdVieneCodificado() {
        DominioConceptoAlumnoRepository repo = mock(DominioConceptoAlumnoRepository.class);
        IdHasher hasher = new IdHasher("clave-de-prueba-123456");

        Semana semana = new Semana();
        semana.setId(7L);
        DominioConceptoAlumno registro = new DominioConceptoAlumno();
        registro.setConcepto("rareza narrativa");
        registro.setProbabilidadDominio(0.4);
        registro.setObservaciones(1);
        registro.setSemana(semana);
        when(repo.findByUsuarioIdOrderByConceptoAsc(1L)).thenReturn(List.of(registro));

        List<Map<String, Object>> mapa = new MapaConocimientoService(repo, hasher).obtenerMapaDeConocimiento(1L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> temas = (List<Map<String, Object>>) mapa.get(0).get("temas");
        assertThat(temas.get(0).get("semanaId")).isEqualTo(hasher.encode(7L));
        assertThat(temas.get(0).get("semanaId")).isNotEqualTo(7L);
    }
}
