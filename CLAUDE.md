# shop-assistant — project conventions

End-to-end AI **Shop Support Assistant**, built in vertical slices. Read this before changing anything.

## Stack
- Java 21, Maven, Spring Boot 3.4.5, **Spring AI 1.0.0**.
- LLM = **Groq** via its OpenAI-compatible API (`spring-ai-starter-model-openai`, base-url points to Groq). Key from env `GROQ_API_KEY`. Do NOT hardcode keys.
- Package: `com.raju.shop`.
- DB (from Slice 1) = Postgres; local dev may use H2, prod = Neon. Vector store (Slice 2) = pgvector on Neon.

## Build / run / test
- Compile: `mvn -q compile`
- Run: `mvn spring-boot:run` (needs `GROQ_API_KEY` in the env)
- Test: `mvn test`
- After ANY change: build + tests green before showing me the diff.

## Architecture (the slices — don't jump ahead)
0. `POST /chat` → `ChatClient` → Groq.  ← current
1. DB tool: `@Tool` methods (e.g. `getOrderStatus(orderId)`) over a Postgres `orders` table.
2. RAG: retrieve return/shipping policy docs from pgvector, add as context.
3. External API tool: `@Tool` calling a shipment-tracking API.
4. Guardrails: input validation, prompt-injection defense, PII redaction, output moderation.
5. MCP: expose the tools via an MCP server.
6. Eval: a golden Q&A set scored for grounding/correctness.
7. Testing: unit + `@WebMvcTest` integration.
8. Deploy: Render + Neon.

## Conventions
- One `ChatClient` built in `ChatController` (or a `@Service` later); attach tools/advisors to it, don't spin up new clients.
- Tools = `@Tool`-annotated methods on a Spring bean, registered via `.tools(bean)` on the prompt.
- Return small `record` DTOs from controllers. Use `@Valid` + `@NotBlank` on request bodies.
- No new dependencies without asking me first.
- Small diffs, one slice at a time.

## Workflow I expect from you
1. For anything non-trivial: short **plan** (files + approach) before code.
2. Implement one slice/change.
3. Run `mvn test`; fix until green.
4. Show the **diff only**, and note edge cases handled.
