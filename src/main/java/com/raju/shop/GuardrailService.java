package com.raju.shop;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

// Slice 4: the safety layer. Four concerns, all in plain code you can read:
//   1. prompt-injection detection (block obvious jailbreak attempts)
//   2. PII redaction (never log raw emails / phones / card numbers)
//   3. output moderation (don't return a reply that leaked the system prompt)
//   (4. input validation for empty/oversized messages is done with bean validation on ChatRequest)
@Service
public class GuardrailService {

    // Crude but illustrative prompt-injection signatures. Real systems use a classifier/LLM-judge too.
    private static final List<Pattern> INJECTION = List.of(
            Pattern.compile("(?i)ignore (all|any|the|your|previous).{0,20}(instruction|prompt|rule)"),
            Pattern.compile("(?i)disregard (the|all|any|previous|above)"),
            Pattern.compile("(?i)(reveal|show|print|expose|repeat).{0,20}(system prompt|your instructions|the prompt)"),
            Pattern.compile("(?i)(you are now|act as (a |an )?(dan|jailbreak|unrestricted))")
    );

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    private static final Pattern PHONE = Pattern.compile("\\b(?:\\+?91[-\\s]?)?[6-9]\\d{9}\\b");
    private static final Pattern CARD  = Pattern.compile("\\b(?:\\d[ -]?){13,16}\\b");

    /** True if the input looks like a prompt-injection / jailbreak attempt. */
    public boolean looksLikeInjection(String input) {
        return INJECTION.stream().anyMatch(p -> p.matcher(input).find());
    }

    /** Replace obvious PII so it never lands in logs or downstream systems. */
    public String redactPii(String text) {
        if (text == null) return null;
        text = CARD.matcher(text).replaceAll("[redacted-card]");
        text = EMAIL.matcher(text).replaceAll("[redacted-email]");
        text = PHONE.matcher(text).replaceAll("[redacted-phone]");
        return text;
    }

    /** True if the model's reply appears to have leaked the system prompt. */
    public boolean outputLeaksSystemPrompt(String reply) {
        String r = reply == null ? "" : reply.toLowerCase();
        return r.contains("you are a helpful support assistant") || r.contains("searchpolicy tool");
    }
}
