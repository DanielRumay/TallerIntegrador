package com.example.tallerintegrador.service;

import com.example.tallerintegrador.entidades.postgres.Usuario;
import com.example.tallerintegrador.repository.UserRepository;
import com.example.tallerintegrador.repository.RespuestaUsuarioRepository;
import com.example.tallerintegrador.repository.SemanaRepository;
import com.example.tallerintegrador.repository.MaterialRepository;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.embedding.Embedding;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Slf4j
@Service
public class PreguntaDedupService {

    private final UserRepository userRepository;
    private final RespuestaUsuarioRepository respuestaUsuarioRepository;
    private final SemanaRepository semanaRepository;
    private final MaterialRepository materialRepository;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    public PreguntaDedupService(
            UserRepository userRepository,
            RespuestaUsuarioRepository respuestaUsuarioRepository,
            SemanaRepository semanaRepository,
            MaterialRepository materialRepository,
            EmbeddingModel embeddingModel,
            @Qualifier("questionsEmbeddingStore") EmbeddingStore<TextSegment> embeddingStore) {
        this.userRepository = userRepository;
        this.respuestaUsuarioRepository = respuestaUsuarioRepository;
        this.semanaRepository = semanaRepository;
        this.materialRepository = materialRepository;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    public Usuario obtenerUsuarioPorEmail(String email) {
        if (email == null || "anonymousUser".equals(email) || email.trim().isEmpty())
            return null;
        return userRepository.findByCorreo(email).orElse(null);
    }

    public Long obtenerSemanaIdPorMongoId(String mongoId) {
        if (mongoId == null || mongoId.trim().isEmpty())
            return null;
        var semanaOpt = semanaRepository.findByMongoId(mongoId);
        if (semanaOpt.isPresent()) {
            return semanaOpt.get().getId();
        }
        var materialOpt = materialRepository.findByMongoId(mongoId);
        if (materialOpt.isPresent()) {
            return materialOpt.get().getSemana().getId();
        }
        return null;
    }

    public List<String> obtenerPreguntasEvitar(String email, String mongoId) {
        Usuario usuario = obtenerUsuarioPorEmail(email);
        if (usuario == null)
            return List.of();
        Long semanaId = obtenerSemanaIdPorMongoId(mongoId);
        if (semanaId == null)
            return List.of();

        // Limitar a las últimas 50 preguntas para no saturar el prompt
        return respuestaUsuarioRepository.findByUsuarioIdAndPreguntaSemanaId(usuario.getId(), semanaId)
                .stream()
                .map(ru -> ru.getPregunta().getPregunta())
                .filter(Objects::nonNull)
                .distinct()
                .limit(50)
                .toList();
    }

    public boolean esPreguntaSimilar(String preguntaTexto, Long usuarioId, List<String> ultimasPreguntas) {
        if (usuarioId == null || preguntaTexto == null || preguntaTexto.trim().isEmpty())
            return false;
        if (ultimasPreguntas == null || ultimasPreguntas.isEmpty())
            return false;

        // Optimización: Si el texto es una coincidencia exacta, rechazar de inmediato
        // sin llamar a la API de embeddings
        if (ultimasPreguntas.contains(preguntaTexto)) {
            log.info("[DEDUP-QDRANT] Coincidencia exacta de texto encontrada en el historial: '{}'", preguntaTexto);
            return true;
        }

        try {
            Response<Embedding> embResponse = embeddingModel.embed(preguntaTexto);
            Embedding queryEmbedding = embResponse.content();

            EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(100)
                    .minScore(0.95) // Umbral de similitud de coseno (optimizado a 0.95 para evitar falsos
                                    // positivos)
                    .build();

            var matches = embeddingStore.search(searchRequest).matches();
            for (var match : matches) {
                var meta = match.embedded().metadata();
                String tipo = meta.getString("tipo");
                String matchUsuarioId = meta.getString("usuarioId");
                String matchText = match.embedded().text();

                if ("pregunta".equals(tipo)
                        && String.valueOf(usuarioId).equals(matchUsuarioId)
                        && ultimasPreguntas.contains(matchText)) {
                    log.info(
                            "[DEDUP-QDRANT] Coincidencia vectorial con una de las últimas 50 preguntas (score={}): '{}' vs '{}'",
                            match.score(), preguntaTexto, matchText);
                    return true;
                }
            }
        } catch (Exception e) {
            log.error("Error al buscar similitud de pregunta en Qdrant: {}", e.getMessage());
        }
        return false;
    }
}
