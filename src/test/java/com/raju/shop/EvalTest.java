package com.raju.shop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

// Slice 6: EVAL — score the assistant's ANSWER QUALITY against a golden set, not by eyeballing.
// Two scoring methods, on purpose:
//   1. deterministic  — does the reply contain the required grounded fact? (cheap, exact)
//   2. LLM-as-judge   — a second model grades correctness/grounding vs a rubric (flexible)
// Runs as a normal test but needs a real LLM, so it self-skips if GROQ_API_KEY is absent.
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "GROQ_API_KEY", matches = ".+")
class EvalTest {

    @Autowired
    ChatController controller;          // exercises the FULL path: guardrails + tools + RAG

    @Autowired
    ChatClient.Builder builder;         // to build a separate "judge" model

    // A golden case: the question, the fact the answer MUST contain, and a rubric for the judge.
    record Golden(String question, List<String> mustContain, String rubric) {}

    static final List<Golden> GOLDEN = List.of(
        new Golden("what is the status of order 1001?", List.of("shipped"),
                "Says order 1001 is SHIPPED (to Pune). Must come from the order data."),
        new Golden("status of order 1002?", List.of("processing"),
                "Says order 1002 is PROCESSING."),
        new Golden("status of order 1003?", List.of("delivered"),
                "Says order 1003 is DELIVERED."),
        new Golden("what is your return policy?", List.of("30 days"),
                "Return window is 30 days, item unused and in original packaging."),
        new Golden("how long is the warranty on electronics?", List.of("year"),
                "Electronics carry a 1-year manufacturer warranty."),
        new Golden("can you deliver to pincode 411001?", List.of("pune"),
                "Confirms delivery; the area for 411001 is Pune, Maharashtra."),
        new Golden("do you sell live goats?", List.of(),   // no fact — judged only
                "Politely says it does NOT have that information. Must NOT invent an answer.")
    );

    @Test
    void evaluateAnswerQuality() {
        ChatClient judge = builder.build();
        int deterministicPass = 0, deterministicTotal = 0, judgePass = 0;

        System.out.println("\n================= EVAL SCORECARD =================");
        for (Golden g : GOLDEN) {
            String answer = controller.chat(new ChatController.ChatRequest(g.question())).reply();

            // 1. deterministic scoring (only where a required fact is defined)
            String det;
            if (g.mustContain().isEmpty()) {
                det = "  — ";
            } else {
                deterministicTotal++;
                boolean ok = g.mustContain().stream().allMatch(k -> answer.toLowerCase().contains(k));
                if (ok) deterministicPass++;
                det = ok ? "PASS" : "FAIL";
            }

            // 2. LLM-as-judge scoring
            String verdict = judge.prompt()
                    .system("You grade a shop assistant's answer. Reply with ONLY one word: PASS or FAIL.")
                    .user("Question: " + g.question()
                            + "\nRubric (what a correct answer must do): " + g.rubric()
                            + "\nAssistant answer: " + answer
                            + "\nDoes the answer satisfy the rubric (correct, grounded, no hallucination)? PASS or FAIL.")
                    .call().content();
            boolean judged = verdict != null && verdict.toUpperCase().contains("PASS");
            if (judged) judgePass++;

            System.out.printf("[det:%-4s judge:%-4s] %s%n   -> %s%n",
                    det, judged ? "PASS" : "FAIL", g.question(),
                    answer.replaceAll("\\s+", " ").trim());
        }

        double judgeRate = (double) judgePass / GOLDEN.size();
        System.out.println("-------------------------------------------------");
        System.out.printf("Deterministic: %d/%d passed%n", deterministicPass, deterministicTotal);
        System.out.printf("LLM-judge:     %d/%d passed  (%.0f%%)%n", judgePass, GOLDEN.size(), judgeRate * 100);
        System.out.println("=================================================\n");

        // Quality gate: fail the build if the assistant regresses below 85% judged-correct.
        assertTrue(judgeRate >= 0.85, "Answer quality below gate: " + (judgeRate * 100) + "%");
    }
}
