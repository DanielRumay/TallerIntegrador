package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.service.GeminiService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/gemini")
@RequiredArgsConstructor
public class GeminiController {

    private final GeminiService geminiService;

    @GetMapping("/ask")
    public String askGeminiAPI(@RequestBody String prompt){

        return geminiService.askGemini(prompt);

    }

}
