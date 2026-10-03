# Shop Support Assistant — Architecture

An end-to-end AI application built in 9 vertical slices. Each slice adds ONE labelled
box below. `[✅]` = built, `[ ]` = pending.

## 1. The big picture (runtime request flow)

```
                          ┌──────────────────────────────────────────────┐
   User                   │            Spring Boot app (Java)             │
 (Postman / curl /        │                                              │
  Invoke-RestMethod / UI) │                                              │
        │                 │                                              │
        │ POST /chat       │                                              │
        │ {"message":...}  │                                              │
        ▼                 │                                              │
  ┌───────────────┐  [✅ Slice 0]                                        │
  │ ChatController │───────────────┐                                     │
  └───────────────┘               │                                     │
        │                          ▼                                     │
        │                 ┌──────────────────────┐  [ Slice 4]           │
        │                 │  INPUT GUARDRAILS     │  validate body,      │
        │                 │  (advisor / filter)   │  detect prompt-       │
        │                 └──────────┬───────────┘  injection, redact PII │
        │                            ▼                                     │
        │                 ┌──────────────────────┐  [✅ Slice 0]          │
        │                 │   ChatClient          │  assembles the prompt: │
        │                 │   (Spring AI)         │  system + context +    │
        │                 │                       │  user + tool specs     │
        │                 └───┬─────────┬────┬────┘                        │
        │                     │         │    │                            │
        │      [ Slice 2]     │         │    │   [ Slice 1 & 3]           │
        │   ┌─────────────────▼──┐      │    └────────────┐               │
        │   │ RAG RETRIEVER      │      │                 ▼               │
        │   │ (top-k policy/FAQ) │      │        ┌──────────────────┐     │
        │   └─────────┬──────────┘      │        │  TOOLS layer     │     │
        │             ▼                 │        │  (@Tool methods) │     │
        │   ┌────────────────────┐      │        ├──────────────────┤     │
        │   │ pgvector  (Neon)   │      │        │ getOrderStatus() │──┐  │
        │   └────────────────────┘      │        │ trackShipment()  │─┐│  │
        │                               │        └──────────────────┘ ││  │
        │                               ▼                              ││  │
        │                     ┌───────────────────┐  [✅ Slice 0]      ││  │
        │                     │   LLM  (Groq)     │                    ││  │
        │                     │  decides: answer  │◄───tool result─────┘│  │
        │                     │  OR call a tool   │────tool call────────┘  │
        │                     └─────────┬─────────┘   (loops until done)   │
        │                               ▼                                  │
        │                 ┌──────────────────────┐  [ Slice 4]            │
        │                 │  OUTPUT GUARDRAILS    │  moderation, PII,      │
        │                 │                       │  grounding check       │
        │                 └──────────┬───────────┘                        │
        ▼                            ▼                                     │
   {"reply": ...}  ◄─────────────────┘                                    │
                          │                                              │
                          └──────────────────────────────────────────────┘

        ┌──────────────────┐  ┌──────────────────┐          DATA STORES
        │ getOrderStatus ──┼─►│ Postgres: orders │  [ Slice 1]  (H2 local → Neon prod)
        │ trackShipment ───┼─►│ External track API│  [ Slice 3]
        └──────────────────┘  └──────────────────┘
```

## 2. Cross-cutting slices (not in the request path)

```
 [ Slice 5] MCP SERVER      exposes the TOOLS layer over the MCP protocol so
                            external clients (Claude Desktop, Cursor) can call them.

 [ Slice 6] EVAL HARNESS    runs a golden Q&A set against /chat, scores each answer
                            for correctness + grounding (a JUnit test, not vibes).

 [ Slice 7] TESTS           unit tests (tools, guardrails) + @WebMvcTest (controller).

 [ Slice 8] DEPLOY          app → Render, DB + pgvector → Neon, /health check, env keys.
```

## 3. Slice-by-slice: what each part is and how it works

| # | Part | What it adds | How it works | Key concept |
|---|------|--------------|--------------|-------------|
| 0 | **Chat endpoint** ✅ | `POST /chat` → LLM | ChatClient sends system+user prompt to Groq, returns text | LLM from Java |
| 1 | **DB tool** | `getOrderStatus(id)` | Model emits a *tool call*; Spring runs the Java method against Postgres; feeds the row back; model answers | tool/function calling |
| 2 | **RAG** | policy/FAQ answers | Retriever embeds the question, pulls top-k doc chunks from pgvector, injects them as context so the model quotes real policy | grounding, embeddings |
| 3 | **API tool** | `trackShipment(id)` | Same as a DB tool but the method calls an external REST API instead of the DB | agents calling the outside world |
| 4 | **Guardrails** | input+output safety | Advisors wrap the ChatClient: validate/redact before, moderate/verify after | prompt-injection defense, PII |
| 5 | **MCP** | tool interoperability | An MCP server publishes the same @Tool methods; any MCP client can discover+call them | MCP protocol |
| 6 | **Eval** | quality gate | A test set of Q→expected runs through /chat; a scorer (or LLM-judge) grades grounding/correctness | evals, not vibes |
| 7 | **Testing** | confidence | Mock the LLM/tools; assert controller + tool behavior | testable AI code |
| 8 | **Deploy** | it's live | Package jar, deploy to Render, point to Neon, set env keys, health check | shipping |

## 4. The mental model (one sentence)
> A `ChatController` calls a Spring AI `ChatClient`; the `ChatClient` gives the **LLM**
> a set of **tools** (DB + API) and a chunk of **RAG** context, wraps the call in
> **guardrails**, exposes the tools via **MCP**, is graded by an **eval** set, covered by
> **tests**, and **deployed** to Render + Neon.
