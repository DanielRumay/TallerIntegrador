package com.example.tallerintegrador.service.analitica;

import com.example.tallerintegrador.entidades.postgres.Intento;
import com.example.tallerintegrador.entidades.postgres.MovimientoMonedas;
import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.repository.MovimientoMonedasRepository;
import com.example.tallerintegrador.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TiendaServiceTest {

    @Mock private MonedasService monedasService;
    @Mock private MovimientoMonedasRepository movimientoRepository;
    @Mock private UserRepository userRepository;

    private TiendaService tienda;
    private Usuario ana;

    @BeforeEach
    void setUp() {
        tienda = new TiendaService(monedasService, movimientoRepository, userRepository);
        ana = new Usuario();
        ana.setId(1L);
    }

    private MovimientoMonedas compra(String clave) {
        return new MovimientoMonedas(1L, -10, MovimientoMonedas.Tipo.GASTO, "x", clave);
    }

    @Test
    void comprarUnAccesorioCobraSuPrecioConClaveUnica() {
        tienda.comprarAccesorio(1L, "gorro");
        verify(monedasService).gastar(1L, 60, "Compraste: Gorro de lana", "accesorio:gorro");
    }

    @Test
    void noSeVendeLoQueNoEstaEnElCatalogo() {
        assertThrows(IllegalArgumentException.class, () -> tienda.comprarAccesorio(1L, "nota-20"));
        verifyNoInteractions(monedasService);
    }

    @Test
    void elProtectorEsSoloParaManana() {
        tienda.protegerManana(1L);
        LocalDate manana = LocalDate.now().plusDays(1);
        verify(monedasService).gastar(eq(1L), eq(50), any(), eq("protector:" + manana));
    }

    @Test
    void soloSeEquipaLoCompradoYUnoPorLugar() {
        when(movimientoRepository.findByUsuarioIdAndClaveStartingWith(1L, "accesorio:"))
                .thenReturn(List.of(compra("accesorio:gorro"), compra("accesorio:corona"), compra("accesorio:bufanda")));

        assertThrows(IllegalArgumentException.class, () -> tienda.equipar(1L, List.of("audifonos")));
        assertThrows(IllegalArgumentException.class, () -> tienda.equipar(1L, List.of("gorro", "corona")));
        verify(userRepository, never()).save(any());

        when(userRepository.findById(1L)).thenReturn(Optional.of(ana));
        assertEquals(Set.of("gorro", "bufanda"), tienda.equipar(1L, List.of("gorro", "bufanda")));
        assertEquals("gorro,bufanda", ana.getAriaEquipado());
        assertEquals(Set.of("gorro", "bufanda"), TiendaService.equipadoDe(ana));
    }

    @Test
    void unDiaProtegidoSostieneLaRachaPeroNoLaCreaDesdeCero() {
        LocalDate hoy = LocalDate.now();
        Intento hoyI = new Intento();
        hoyI.setFecha(hoy.atTime(10, 0));
        Intento antier = new Intento();
        antier.setFecha(hoy.minusDays(2).atTime(10, 0));

        // Hoy y antier practicó; ayer estaba protegido: la racha sigue en 3.
        assertEquals(3, ProgresoAlumnoService.calcularRacha(List.of(hoyI, antier), Set.of(hoy.minusDays(1))));
        // Sin el protector se corta en 1.
        assertEquals(1, ProgresoAlumnoService.calcularRacha(List.of(hoyI, antier), Set.of()));
        // Sin práctica no hay racha, aunque haya protectores.
        assertEquals(0, ProgresoAlumnoService.calcularRacha(List.of(), Set.of(hoy)));
    }
}
