package com.example.tallerintegrador.service.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Estado de una ingesta en curso, para que el docente vea que esta pasando.
 *
 * POR QUE EXISTE. Ingerir una obra larga puede tardar varios minutos: se leen las paginas, se
 * pide un resumen por capitulo y se embeben cientos de fragmentos. Sin senal de progreso, el
 * docente ve una pantalla congelada, supone que se colgo y recarga — y entonces si queda una
 * ingesta a medias, que es peor que no haber empezado.
 *
 * DECISION: el estado vive EN MEMORIA, no en base de datos. Es informacion efimera de una
 * operacion en curso; si el servidor se reinicia, esa ingesta se perdio de todos modos y
 * conservar su barra de progreso no sirve de nada. Guardarlo en Postgres anadiria escrituras
 * constantes durante la ingesta a cambio de nada.
 *
 * LIMITE CONOCIDO Y DECLARADO: con varias instancias del backend detras de un balanceador,
 * una consulta de progreso podria llegar a la instancia que no ejecuta esa ingesta y
 * responder "no encontrada". Para el despliegue de esta tesis —una sola instancia— no ocurre.
 * Si algun dia se escala horizontalmente, esto pasa a Redis o a una tabla.
 */
@Slf4j
@Service
public class ProgresoIngestaService {

    /** Fases del canal, con el porcentaje en el que ARRANCA cada una. */
    public enum Fase {
        SUBIENDO(5, "Subiendo el documento"),
        LEYENDO(15, "Leyendo el documento"),
        SEGMENTANDO(30, "Reconociendo capitulos y secciones"),
        SUBTEMAS(40, "Identificando los temas"),
        TROCEANDO(55, "Preparando el contenido"),
        RESUMIENDO(65, "Resumiendo cada seccion"),
        VECTORIZANDO(80, "Extrayendo vectores"),
        GUARDANDO(95, "Guardando el material"),
        TERMINADO(100, "Listo"),
        ERROR(100, "No se pudo procesar");

        public final int porcentaje;
        public final String mensaje;

        Fase(int porcentaje, String mensaje) {
            this.porcentaje = porcentaje;
            this.mensaje = mensaje;
        }
    }

    public static class Progreso {
        public String id;
        public String nombreArchivo;
        public Fase fase;
        public int porcentaje;
        public String mensaje;
        public String detalle;
        public boolean terminado;
        public boolean exitoso;
        public String error;
        public LocalDateTime inicio;
        public LocalDateTime actualizado;
    }

    private final Map<String, Progreso> enCurso = new ConcurrentHashMap<>();

    /** Pasada esta antiguedad, una entrada terminada se descarta al hacer limpieza. */
    private static final long MINUTOS_RETENCION = 30;

    public String iniciar(String nombreArchivo) {
        limpiarViejas();

        Progreso p = new Progreso();
        p.id = UUID.randomUUID().toString();
        p.nombreArchivo = nombreArchivo;
        p.fase = Fase.SUBIENDO;
        p.porcentaje = Fase.SUBIENDO.porcentaje;
        p.mensaje = Fase.SUBIENDO.mensaje;
        p.inicio = LocalDateTime.now();
        p.actualizado = p.inicio;

        enCurso.put(p.id, p);
        return p.id;
    }

    /**
     * @param detalle texto opcional con la cifra concreta ("120 de 513 fragmentos"), que es lo
     *                que convierte una barra que parece colgada en una que se ve avanzar.
     */
    public void actualizar(String id, Fase fase, String detalle) {
        if (id == null) return;
        Progreso p = enCurso.get(id);
        if (p == null) return;

        p.fase = fase;
        p.porcentaje = fase.porcentaje;
        p.mensaje = fase.mensaje;
        p.detalle = detalle;
        p.actualizado = LocalDateTime.now();
    }

    /**
     * Progreso fino DENTRO de una fase, para que la barra no se quede quieta durante los
     * minutos que dura vectorizar. Interpola entre el porcentaje de esta fase y el de la
     * siguiente, sin salirse nunca de ese tramo.
     */
    public void actualizarParcial(String id, Fase fase, Fase siguiente, int hechos, int total, String detalle) {
        if (id == null || total <= 0) return;
        Progreso p = enCurso.get(id);
        if (p == null) return;

        int tramo = Math.max(0, siguiente.porcentaje - fase.porcentaje);
        int avance = (int) Math.round(tramo * Math.min(1.0, hechos / (double) total));

        p.fase = fase;
        p.porcentaje = Math.min(siguiente.porcentaje, fase.porcentaje + avance);
        p.mensaje = fase.mensaje;
        p.detalle = detalle;
        p.actualizado = LocalDateTime.now();
    }

    public void terminar(String id, boolean exitoso, String error) {
        if (id == null) return;
        Progreso p = enCurso.get(id);
        if (p == null) return;

        p.fase = exitoso ? Fase.TERMINADO : Fase.ERROR;
        p.porcentaje = 100;
        p.mensaje = p.fase.mensaje;
        p.terminado = true;
        p.exitoso = exitoso;
        p.error = error;
        p.actualizado = LocalDateTime.now();
    }

    public Map<String, Object> consultar(String id) {
        Progreso p = enCurso.get(id);
        if (p == null) {
            // Puede ser un id inventado o una ingesta ya purgada. Se responde con una forma
            // valida en vez de un error, para que el frontend no tenga que distinguir casos.
            Map<String, Object> vacio = new LinkedHashMap<>();
            vacio.put("encontrada", false);
            vacio.put("terminado", true);
            vacio.put("porcentaje", 100);
            vacio.put("mensaje", "No hay informacion de esta carga");
            return vacio;
        }

        Map<String, Object> salida = new LinkedHashMap<>();
        salida.put("encontrada", true);
        salida.put("id", p.id);
        salida.put("nombreArchivo", p.nombreArchivo);
        salida.put("fase", p.fase.name());
        salida.put("porcentaje", p.porcentaje);
        salida.put("mensaje", p.mensaje);
        salida.put("detalle", p.detalle);
        salida.put("terminado", p.terminado);
        salida.put("exitoso", p.exitoso);
        salida.put("error", p.error);
        return salida;
    }

    private void limpiarViejas() {
        LocalDateTime corte = LocalDateTime.now().minusMinutes(MINUTOS_RETENCION);
        enCurso.entrySet().removeIf(e -> e.getValue().actualizado.isBefore(corte));
    }
}
