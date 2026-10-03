package com.raju.shop;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagConfig {

    // Embedding model = nomic-embed-text, served LOCALLY by Ollama (http://localhost:11434).
    //   - 768 dimensions (richer than the old all-MiniLM-L6-v2's 384)
    //   - free, private, offline — same reasons you'd self-host in a privacy-sensitive enterprise
    // We build it by hand (not via the Ollama Spring Boot starter) so that only the EMBEDDING
    // model comes from Ollama; the CHAT model stays on Groq. No bean conflict.
    @Bean
    public EmbeddingModel embeddingModel() {
        OllamaApi ollamaApi = OllamaApi.builder()
                .baseUrl("http://localhost:11434")
                .build();

        return OllamaEmbeddingModel.builder()
                .ollamaApi(ollamaApi)
                .defaultOptions(OllamaEmbeddingOptions.builder()
                        .model("nomic-embed-text")
                        .build())
                .build();
    }
}
