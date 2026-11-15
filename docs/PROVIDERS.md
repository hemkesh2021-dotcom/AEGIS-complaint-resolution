# AI providers in AEGIS 4

Keys belong in the API server's environment or your deployment secret manager. The browser only displays configuration status and generates a placeholder configuration. It never collects or returns provider keys. Operator credentials and provider credentials are separate.

## Anthropic

In your gitignored `.env`:

```dotenv
AEGIS_LLM_PROVIDER=anthropic
ANTHROPIC_API_KEY=your-key
AEGIS_LLM_MODEL=your-available-claude-model-id
```

Use a model ID enabled for your account, not the placeholder above. Run `docker compose up -d --build api`. Open AI providers in the console, authenticate as a supervisor, and use Test connection. The test makes one billable model call containing no customer data.

The native Anthropic adapter calls `/v1/messages` with `x-api-key`, `anthropic-version`, a top-level system instruction, messages and `max_tokens`. The compatibility version defaults to `2023-06-01`; override `ANTHROPIC_VERSION` when your provider requires a different supported version. Text blocks are combined; truncated responses fail into the template path.

## Supported destinations

| AEGIS_LLM_PROVIDER | Key variable | Default API base (includes version path) |
|---|---|---|
| nim | NIM_API_KEY | https://integrate.api.nvidia.com/v1 |
| anthropic | ANTHROPIC_API_KEY | https://api.anthropic.com/v1 |
| openai | OPENAI_API_KEY | https://api.openai.com/v1 |
| gemini | GEMINI_API_KEY | https://generativelanguage.googleapis.com/v1beta/openai |
| groq | GROQ_API_KEY | https://api.groq.com/openai/v1 |
| openrouter | OPENROUTER_API_KEY | https://openrouter.ai/api/v1 |
| ollama | OLLAMA_API_KEY (optional) | http://localhost:11434/v1 |
| custom | AEGIS_LLM_API_KEY | Set AEGIS_LLM_BASE_URL |
| template | None | No external call |

Set `AEGIS_LLM_MODEL` to a text-chat model compatible with the selected endpoint. NIM retains `NIM_CHAT_MODEL` and its previous default when no override is set. Other providers require an explicit model ID, preventing stale hard-coded model defaults. Support is for synchronous text generation, not every vendor feature or every model.

For Ollama running on the Docker host, set `AEGIS_LLM_BASE_URL=http://host.docker.internal:11434/v1` and `AEGIS_LLM_ALLOW_HTTP=true`; pull the desired model into Ollama first. Linux Docker may need a host-gateway mapping. Never expose an unauthenticated Ollama port publicly.

Custom bases must include the version path and omit `/chat/completions`; the adapter appends it. HTTPS is required except loopback or explicit HTTP opt-in. Redirects are disabled. A base URL is trusted administrator configuration: review where it sends prompts and keys before changing it. There is no automatic failover to a different provider.

## Operation

- `AEGIS_LLM_TIMEOUT_SECONDS`: 1-120, default 30; covers an individual generation request. Multiple pipeline generations can take longer in total.
- `GET /api/providers`: protected metadata and process-local success/failure counters. Configured means key/model present, not a successful network probe.
- `POST /api/providers/test`: supervisor only (or the development API key). It is an explicit, billable connection test.
- Missing key/model, network error, HTTP failure, malformed/blank or truncated completion: drafting falls back to templates, summary falls back to an extract, translation uses its existing fallback.
- Existing redaction runs before every external prompt. It is best-effort, not a guarantee of complete anonymization. Grounding verification and human approval still apply after generation.

These adapters were tested against local HTTP fixtures. No paid provider was called during the release verification. Validate your chosen provider/model in your deployment before using real cases. See [SECURITY.md](../SECURITY.md) for the broader deployment boundary.
