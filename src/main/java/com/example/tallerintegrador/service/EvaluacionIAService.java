package com.example.tallerintegrador.service;

import com.example.tallerintegrador.entidades.mongodb.ArchivoPrompt;
import com.example.tallerintegrador.entidades.mongodb.Prompt;
import com.example.tallerintegrador.repository.ArchivoPromptRepository;
import com.example.tallerintegrador.repository.PromptRepository;
import lombok.RequiredArgsConstructor;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EvaluacionIAService {

    private final GeminiService geminiService;
    private final PromptTemplateService promptTemplateService;
    private final TikaExtractorService tikaExtractorService;
    private final PromptRepository promptRepository;
    private final ArchivoPromptRepository archivoPromptRepository;

    private final String UPLOAD_DIR = "uploads/";

    public Prompt procesarArchivo(
            MultipartFile archivo,
            Long usuarioId,
            String tecnica,
            String tipoPregunta,
            String nivelBloom,
            int cantidad,
            String promptUsuario
    ) throws IOException, TikaException {

        // 1. Crear carpeta uploads
        Files.createDirectories(Paths.get(UPLOAD_DIR));

        // 2. Nombre único archivo
        String nombreArchivo =
                UUID.randomUUID() + "_"
                        + archivo.getOriginalFilename();

        // 3. Ruta final
        Path rutaArchivo =
                Paths.get(UPLOAD_DIR, nombreArchivo);

        // 4. Guardar archivo físicamente
        Files.copy(
                archivo.getInputStream(),
                rutaArchivo
        );

        // 5. Extraer texto PDF
        String textoExtraido =
                tikaExtractorService.extractText(archivo);

        // 6. Construir prompt sistema
        String promptSistema =
                promptTemplateService.build(
                        tecnica,
                        tipoPregunta,
                        nivelBloom,
                        textoExtraido,
                        cantidad
                );

        // 7. Construir prompt final
        String promptFinal = promptSistema;

        if (promptUsuario != null
                && !promptUsuario.isBlank()) {

            promptFinal += """

                    INSTRUCCIONES ADICIONALES DEL USUARIO:
                    """ + promptUsuario;
        }

        // 8. Consultar Gemini
        String respuestaIA =
                geminiService.askGemini(promptFinal);

        // 9. Crear metadata archivo
        ArchivoPrompt archivoPrompt =
                new ArchivoPrompt();

        archivoPrompt.setId(
                UUID.randomUUID().toString()
        );

        archivoPrompt.setNombre(
                archivo.getOriginalFilename()
        );

        archivoPrompt.setTipo(
                archivo.getContentType()
        );

        archivoPrompt.setUrl(
                rutaArchivo.toString()
        );

        // 10. Crear documento Mongo
        Prompt prompt = new Prompt();

        prompt.setUsuarioId(usuarioId);

        prompt.setPromptSistema(promptSistema);

        prompt.setPromptUsuario(promptUsuario);

        prompt.setTextoExtraido(textoExtraido);

        prompt.setPromptFinal(promptFinal);

        prompt.setRespuestaIA(respuestaIA);

        prompt.setFechaCreacion(
                LocalDateTime.now()
        );

        ArchivoPrompt archivoGuardado =
                archivoPromptRepository.save(archivoPrompt);

        prompt.setArchivos(
                List.of(archivoGuardado)
        );

        // 11. Guardar Mongo
        return promptRepository.save(prompt);
    }
}