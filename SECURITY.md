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
- **Rate limiting** on the public intake endpoint (per client IP).
- **Input limits** on request size; error messages and stack traces are suppressed in responses.
- **Containers** run as a non-root user; API keys are constant-time compared.

## Hardening notes for deployers

This is a portfolio/reference project. Before any production use:

- Replace the shared API key with real user auth (OAuth2 / JWT).
- Terminate **TLS/HTTPS** and put the API behind a WAF.
- Move all secrets (NIM key, DB credentials) into a secret manager; rotate them.
- Do **not** process real customer PII without appropriate legal and privacy controls. The bundled regulation knowledge base is illustrative and is **not** legal advice.
