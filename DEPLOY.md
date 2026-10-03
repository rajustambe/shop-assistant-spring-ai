# Deploying shop-assistant

The app is a single container. **All config comes from environment variables** — nothing
secret lives in the image or in git. The same image runs on Render, AWS ECS, or anywhere.

## Environment variables

| Var | Required | What |
|---|---|---|
| `GROQ_API_KEY` | yes | LLM API key (secret) |
| `PORT` | injected by PaaS | port to bind (defaults to 8080) |
| `SHOP_MCP_TOKEN` | recommended | bearer token required on `/mcp`. Maps to `shop.mcp.token`. Blank = MCP auth off |
| `SHOP_NEON_URL` | optional | pgvector Postgres URL for persistent RAG. Maps to `shop.neon.url`. Blank = in-memory |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | optional | point the **orders** DB at managed Postgres instead of in-memory H2 |
| `SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL` | optional | override the model |

> Property↔env mapping is Spring's relaxed binding: `shop.mcp.token` → `SHOP_MCP_TOKEN`,
> `shop.neon.url` → `SHOP_NEON_URL`.

## Health check
`GET /actuator/health` → `{"status":"UP"}`. Point the load balancer / container healthcheck here.

## Build & run locally (Docker)
```bash
docker build -t shop-assistant .
docker run -p 8080:8080 \
  -e GROQ_API_KEY=gsk_... \
  -e SHOP_MCP_TOKEN=shop-secret-token-abc123 \
  shop-assistant
# then: curl localhost:8080/actuator/health
```

## Deploy to Render (simplest)
1. Push this project to a GitHub repo.
2. Render → New → **Web Service** → connect the repo. Render detects the `Dockerfile`.
3. Add env vars in the dashboard: `GROQ_API_KEY`, `SHOP_MCP_TOKEN` (Render sets `PORT`).
4. Health check path: `/actuator/health`. Deploy → you get a public HTTPS URL.

## Deploy to AWS (production shape)
1. Build & push the image to **ECR**.
2. Run it on **ECS Fargate** (2+ tasks) behind an **ALB**; target-group healthcheck = `/actuator/health`.
3. Secrets (`GROQ_API_KEY`, DB creds) in **Secrets Manager**, injected into the task as env vars.
4. Orders DB + pgvector on **RDS/Aurora Postgres**: set `SPRING_DATASOURCE_*` and `SHOP_NEON_URL`.
5. Logs/metrics → **CloudWatch**; put **API Gateway/WAF** in front for auth + rate-limiting.

## Notes
- `spring.jpa.hibernate.ddl-auto=create` recreates the schema on boot — fine for a demo, but
  for a persistent Postgres set it to `validate` or `update` so data survives restarts.
- CI should run `mvn test` (unit tests + the eval gate) BEFORE building/pushing the image.
