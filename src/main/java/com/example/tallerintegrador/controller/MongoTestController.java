package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.entidades.mongodb.Prompt;
import com.example.tallerintegrador.service.PromptMongoService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mongo")
@RequiredArgsConstructor
public class MongoTestController {

    private final PromptMongoService promptMongoService;

    @PostMapping("/test")
    public Prompt testMongo() {

        return promptMongoService.guardarPrompt();
    }
}