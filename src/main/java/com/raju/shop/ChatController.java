package com.raju.shop;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

// Slice 0: the thinnest possible "LLM from Java" endpoint.
// Later slices attach tools (DB/API), RAG context, and guardrails to this same ChatClient.
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatClient chat;
    private final GuardrailService guard;

    public ChatController(ChatClient chat, GuardrailService guard) {
        this.chat = chat;      // injected bean (real LLM in prod, mock in tests)
        this.guard = guard;
    }

    // @NotBlank/@Size => empty or oversized messages are rejected with HTTP 400 automatically.
    public record ChatRequest(@NotBlank @Size(max = 1000) String message) {}
    public record ChatResponse(String reply) {}

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest req) {
        // GUARD 1 (input): block obvious prompt-injection before it reaches the model.
        if (guard.looksLikeInjection(req.message())) {
            log.warn("Blocked possible prompt injection: {}", guard.redactPii(req.message()));
            return new ChatResponse("Sorry, I can't help with that request.");
        }

        // GUARD 2 (privacy): log only a PII-redacted copy of the message.
        log.info(">>> /chat IN : {}", guard.redactPii(req.message()));

        log.info("... calling LLM (it will decide which tools to call) ...");
        String reply = chat.prompt()
                .user(req.message())
                .call()
                .content();

        // GUARD 3 (output): if the reply leaked the system prompt, don't return it.
        if (guard.outputLeaksSystemPrompt(reply)) {
            log.warn("Output guard tripped — reply looked like a system-prompt leak.");
            return new ChatResponse("Sorry, I can't share that.");
        }
        log.info("<<< /chat OUT: {}", reply);
        return new ChatResponse(reply);
    }
}
