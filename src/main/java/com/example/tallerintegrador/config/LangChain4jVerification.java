package com.example.tallerintegrador.config;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class LangChain4jVerification {

    private final ChatModel chatModel;

    @PostConstruct
    public void verify() {
        try {
            UserMessage userMessage = UserMessage.from("Di exactamente: LangChain4j operativo");

            ChatRequest request = ChatRequest.builder()
                    .messages(userMessage)
                    .build();
            ChatResponse response = chatModel.chat(request);
            log.info("LangChain4j verificado: {}", response.aiMessage().text());

        } catch (Exception e) {
            log.error("LangChain4j falló: {}", e.getMessage());
        }
    }
}