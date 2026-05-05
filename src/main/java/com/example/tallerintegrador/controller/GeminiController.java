package com.example.tallerintegrador.controller;

import com.example.tallerintegrador.service.GeminiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/gemini")
@RequiredArgsConstructor
public class GeminiController {

    private final GeminiService geminiService;

    public record GeminiRequest(String prompt) {}

    @PostMapping("/ask")
    public String askGeminiAPI(@RequestBody GeminiRequest request){
        var responseObj = geminiService.askGemini(request.prompt());
        return responseObj.text();
    }

    @GetMapping(value = "/ask-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askGeminiStreamAPI(@RequestParam String prompt) {

        SseEmitter emitter = new SseEmitter(600000L);

        CompletableFuture.runAsync(() -> {
            try {
                geminiService.askGeminiStream(prompt).forEach(response -> {
                    try {
                        emitter.send(response.text());
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                });
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

}