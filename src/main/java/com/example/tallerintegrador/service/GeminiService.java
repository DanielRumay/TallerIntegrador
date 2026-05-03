package com.example.tallerintegrador.service;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class GeminiService {

    private final Client client;

    public String askGemini(String prompt){
        GenerateContentResponse response =
                client.models.generateContent("gemini-3-flash-preview", prompt, null);
        return response.text();
    }
    public Iterable<GenerateContentResponse> askGeminiStream(String prompt){
        return client.models.generateContentStream(
                "gemini-3-flash-preview", prompt,
                null);
    }
}