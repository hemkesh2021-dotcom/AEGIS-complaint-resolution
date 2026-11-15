# AEGIS 4.0.0 - Workspace and provider choice

> Historical release notes. Superseded by [v4.0.1](RELEASE-v4.0.1.md), which restores the original interface at the owner's request. The interface features described below are not present in the current release. Backend provider support is retained.

AEGIS 4 adds a redesigned light/dark operator workspace, faster triage and multiple AI providers while retaining the verified approval and privacy fixes on main.

## Shipped

- Search recent cases; sort by urgency, deadline or date; filter overdue work.
- Save/delete personal queue views; export filtered metadata to CSV; navigate with a command palette.
- Neumorphic controls, light/dark themes, responsive operator and customer surfaces, and an empty customer intake form by default.
- Anthropic native Messages plus NIM, OpenAI, Gemini, Groq, OpenRouter, Ollama and custom compatible providers. Server-side keys, explicit model selection, bounded timeouts, sanitized errors and template fallback.
- Protected provider diagnostics and supervisor-only connection probe.
- Maximum/Balanced/Low power graphics, 4096px maximum shadows, up to 2.5x pixel ratio, 5,000 particles, theme-aware bloom/FXAA, non-overlapping labels, facing display panels, component navigation, zoom/reset, motion controls and PNG capture.
- Reproducible Java tests and Playwright workflow tests, with browser CI.

## Upgrade

1. Back up your database and deployment configuration.
2. Compare `.env.example` with your current environment. Existing NIM key/model settings still work when `AEGIS_LLM_PROVIDER` is unset. The new example defaults to `template`.
3. For Claude, set `AEGIS_LLM_PROVIDER=anthropic`, `ANTHROPIC_API_KEY` and `AEGIS_LLM_MODEL` to an enabled model ID.
4. Rebuild/restart the API and web services: `docker compose up -d --build`.
5. Sign in, check AI providers, optionally run a connection test, and review a test complaint before using real data.

No database schema change is introduced by v4. Backend artifact version is now `4.0.0`. The historical directory name `aegis-v2` can remain unchanged.

## Verification and limits

All 109 Java unit/integration tests passed locally, covering provider HTTP contracts, authorization, approval, redaction, grounding and intake concurrency. Browser checks passed with synthetic fixtures for themes, searches, saved views, CSV downloads, provider status/probe UX, responsive layout and WebGL interactions on desktop/mobile. The release build was tested locally with Java 26 targeting Java 21 (`-Dnet.bytebuddy.experimental=true` for the host JDK); CI uses Java 21.

Live paid-provider calls and the complete Docker stack were not exercised locally: no provider credentials were used and the Docker daemon was unavailable. Browser fonts, Three.js and icons currently require their configured CDNs. Maximum mode may be expensive on phones; select Balanced or Low power as needed. Light mode intentionally disables bloom to avoid washing out the scene. Existing regulatory rules and grounding heuristics need domain review; this release is not regulatory certification, an SLA guarantee, or a hosted SaaS launch.

See [PROVIDERS.md](PROVIDERS.md) for keys and compatibility, [V4-RESEARCH.md](V4-RESEARCH.md) for sources and feature boundaries, and [SECURITY.md](../SECURITY.md) before deployment.
