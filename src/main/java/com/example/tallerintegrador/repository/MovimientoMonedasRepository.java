package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.postgres.MovimientoMonedas;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MovimientoMonedasRepository extends JpaRepository<MovimientoMonedas, Long> {

    boolean existsByUsuarioIdAndClave(Long usuarioId, String clave);

    List<MovimientoMonedas> findTop20ByUsuarioIdOrderByFechaDesc(Long usuarioId);

    /** Movimientos con clave de un tipo de artículo, p. ej. "accesorio:" o "protector:". */
    List<MovimientoMonedas> findByUsuarioIdAndClaveStartingWith(Long usuarioId, String prefijo);

    @Query("select coalesce(sum(m.cantidad), 0) from MovimientoMonedas m where m.usuarioId = :usuarioId and m.tipo = :tipo")
    int sumaPorTipo(@Param("usuarioId") Long usuarioId, @Param("tipo") MovimientoMonedas.Tipo tipo);
}
