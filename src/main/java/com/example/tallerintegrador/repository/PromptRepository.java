package com.example.tallerintegrador.repository;

import com.example.tallerintegrador.entidades.mongodb.Prompt;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PromptRepository
        extends MongoRepository<Prompt, String> {
}