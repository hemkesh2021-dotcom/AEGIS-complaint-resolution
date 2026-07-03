# Security Policy

## Reporting a vulnerability

Please report security issues **privately** — do not open a public GitHub issue.

- Use GitHub's **[Private vulnerability reporting](https://github.com/hemkesh2021-dotcom/AEGIS-complaint-resolution/security/advisories/new)** (Security tab → "Report a vulnerability"), or
- email **hemkesh.2021@gmail.com** with details and steps to reproduce.

You'll get an acknowledgement as soon as possible. Please give a reasonable window to address the issue before any public disclosure.

## Supported versions

| Version | Supported |
|---|---|
| `main` / latest release | ✅ |
| older | ❌ |

## Security posture

The API ships with a baseline security gate:

- **Authentication:** operator + `/actuator/metrics` endpoints require an `X-API-Key`; only intake, status, and health are public.
- **CORS** restricted to configured origins (not a wildcard).
- **Rate limiting** on both public endpoints (per client IP): intake (LLM-cost abuse) and status (token-guessing).
- **Input limits** on request size; error messages and stack traces are suppressed in responses.
- **Containers** run as a non-root user; API keys are constant-time compared.
- **Secret scanning:** gitleaks runs in CI on every push (full history).

## AI-output safety (grounding gate)

Every AI-drafted reply passes a **grounding check** at draft time and again at send time:

- Monetary amounts and phone numbers in the reply must appear in the complaint, the retrieved
  regulation context, or the case facts — invented figures block the send (an operator can
  override, and every override is written to the audit trail).
- Template debris — `[Bank Name]`-style placeholders, unfilled `{tokens}`, raw ISO
  timestamps — also blocks the send.

## Data protection (PII)

- **Trust boundary:** classification and embeddings run locally. Only drafting/summarization
  calls an external LLM, and those prompts are **redacted first** — emails, phone numbers,
  card/account numbers, and SSN-like ids are stripped, and the customer's name is replaced by a
  `{CUSTOMER_NAME}` token that is substituted back locally after generation.
- **Status lookups** use a 128-bit random tracking token (`TRK-…`), not the short CMP reference —
  references are never accepted on the public status endpoint, so cases can't be enumerated.
- **Retention:** a daily purge job deletes cases + audit events older than
  `AEGIS_RETENTION_DAYS` (0 = disabled for dev; set a real policy before processing real data).

## Hardening notes for deployers

This is a portfolio/reference project. Before any production use:

- Replace the shared API key with real user auth (OAuth2 / JWT).
- Terminate **TLS/HTTPS** and put the API behind a WAF.
- Move all secrets (NIM key, DB credentials) into a secret manager; rotate them.
- Do **not** process real customer PII without appropriate legal and privacy controls. The bundled regulation knowledge base is illustrative and is **not** legal advice.
