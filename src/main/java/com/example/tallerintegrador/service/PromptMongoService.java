package com.example.tallerintegrador.service;

import com.example.tallerintegrador.entidades.mongodb.Prompt;
import com.example.tallerintegrador.repository.PromptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PromptMongoService {

    private final PromptRepository promptRepository;

    public Prompt guardarPrompt() {

        Prompt prompt = new Prompt();

        prompt.setUsuarioId(1L);
        prompt.setContenido("Prompt de prueba");
        prompt.setRespuestaIA("Respuesta IA de prueba");

        return promptRepository.save(prompt);
    }
}