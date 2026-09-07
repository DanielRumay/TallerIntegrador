package com.example.tallerintegrador.service.rag;
import com.example.tallerintegrador.service.academico.ArchivoService;
import com.example.tallerintegrador.service.academico.IntentoService;
import com.example.tallerintegrador.service.academico.SemanaService;

import com.example.tallerintegrador.entidades.postgres.Material;
import com.example.tallerintegrador.entidades.postgres.Semana;
import com.example.tallerintegrador.repository.MaterialRepository;
import com.example.tallerintegrador.repository.SemanaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Ingesta en segundo plano, para que el docente no espere con la pantalla congelada.
 *
 * EL DETALLE QUE ROMPE ESTO SI SE HACE MAL. Un MultipartFile esta respaldado por un fichero
 * temporal que Spring BORRA en cuanto termina la peticion HTTP. Si se pasa tal cual a un hilo
 * de fondo, ese hilo intentara leerlo cuando ya no existe y la ingesta fallara con un error
 * de fichero no encontrado, de forma intermitente y dificil de reproducir. Por eso aqui se
 * copian los BYTES en memoria antes de devolver la respuesta, y el trabajo de fondo opera
 * sobre esa copia.
 *
 * Es tambien la razon de que exista `MultipartEnMemoria`: el canal de ingesta espera un
 * MultipartFile, y esta clase se lo da sin depender del fichero temporal.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestaAsincronaService {

    private final RagIngestionService ragIngestionService;
    private final ProgresoIngestaService progresoIngestaService;
    private final SemanaRepository semanaRepository;
    private final MaterialRepository materialRepository;
    private final ArchivoService archivoService;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;

    /**
     * Copia los bytes AHORA, en el hilo de la peticion, y lanza el procesado al fondo.
     *
     * @return el id de progreso, que el frontend consulta para pintar la barra.
     */
    public String lanzar(Long semanaId, List<MultipartFile> archivos) throws IOException {
        String nombres = archivos.stream()
                .map(MultipartFile::getOriginalFilename)
                .reduce((a, b) -> a + ", " + b)
                .orElse("documento");

        String progresoId = progresoIngestaService.iniciar(nombres);

        // Copia en memoria ANTES de que Spring borre los temporales.
        List<MultipartFile> copias = new ArrayList<>();
        for (MultipartFile a : archivos) {
            copias.add(new MultipartEnMemoria(
                    a.getName(), a.getOriginalFilename(), a.getContentType(), a.getBytes()));
        }

        CompletableFuture.runAsync(() -> procesar(semanaId, copias, progresoId));
        return progresoId;
    }

    private void procesar(Long semanaId, List<MultipartFile> archivos, String progresoId) {
        try {
            for (MultipartFile archivo : archivos) {
                var resultado = ragIngestionService.ingestarArchivo(archivo, progresoId);

                if (!resultado.exitoso()) {
                    progresoIngestaService.terminar(progresoId, false, resultado.errorMensaje());
                    return;
                }

                progresoIngestaService.actualizar(progresoId, ProgresoIngestaService.Fase.GUARDANDO, null);
                registrarMaterial(semanaId, archivo.getOriginalFilename(), resultado.archivoId());
            }
            progresoIngestaService.terminar(progresoId, true, null);
        } catch (Exception e) {
            log.error("[INGESTA-ASYNC] Falló el procesado: {}", e.getMessage(), e);
            progresoIngestaService.terminar(progresoId, false, e.getMessage());
        }
    }

    /**
     * Se abre una transacción propia AQUÍ y no alrededor de todo el procesado: mantenerla
     * abierta durante los minutos que dura la ingesta bloquearía una conexión del pool sin
     * necesidad, y un fallo al final desharía trabajo ya válido.
     *
     * SE USA TransactionTemplate Y NO @Transactional POR UN MOTIVO CONCRETO. Este método se
     * llama desde `procesar()`, que está en esta misma clase. `@Transactional` de Spring
     * funciona mediante un proxy que envuelve al bean: una llamada interna (`this.metodo()`)
     * no pasa por ese proxy, así que la anotación **no habría hecho nada** y la transacción
     * que el comentario prometía no habría existido. Es un fallo silencioso clásico: el
     * código parece transaccional, compila, funciona en el caso feliz, y solo se nota cuando
     * algo falla a mitad y los datos quedan inconsistentes.
     *
     * `TransactionTemplate` no depende del proxy: abre la transacción de forma explícita.
     * Es además el patrón que ya usa IntentoService.guardarIntentoCompleto.
     */
    private void registrarMaterial(Long semanaId, String nombreArchivo, String archivoId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Semana semana = semanaRepository.findById(semanaId)
                    .orElseThrow(() -> new RuntimeException("Semana no encontrada: " + semanaId));

            Material material = new Material();
            material.setNombreArchivo(nombreArchivo);
            material.setMongoId(archivoId);
            material.setSemana(semana);
            material.setVisible(true);
            materialRepository.save(material);

            // Mismo criterio que SemanaService.subirArchivos: el nombre del tema se deriva de
            // los subtemas solo si el docente no puso uno propio.
            if (semana.getNombreTema() == null || semana.getNombreTema().isBlank()) {
                List<String> subtemas = archivoService.obtenerSubtemas(archivoId);
                if (subtemas != null && !subtemas.isEmpty()) {
                    semana.setNombreTema(
                            String.join(" · ", subtemas.subList(0, Math.min(2, subtemas.size()))));
                    semanaRepository.save(semana);
                }
            }
        });
    }

    /** MultipartFile respaldado por un array de bytes, no por un fichero temporal. */
    private record MultipartEnMemoria(
            String name, String originalFilename, String contentType, byte[] contenido)
            implements MultipartFile {

        @Override public String getName() { return name; }
        @Override public String getOriginalFilename() { return originalFilename; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return contenido == null || contenido.length == 0; }
        @Override public long getSize() { return contenido == null ? 0 : contenido.length; }
        @Override public byte[] getBytes() { return contenido; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(contenido); }
        @Override public org.springframework.core.io.Resource getResource() {
            return new ByteArrayResource(contenido);
        }
        @Override public void transferTo(java.io.File destino) throws IOException {
            try (OutputStream os = Files.newOutputStream(destino.toPath())) {
                os.write(contenido);
            }
        }
        @Override public void transferTo(Path destino) throws IOException {
            Files.write(destino, contenido);
        }
    }
}
