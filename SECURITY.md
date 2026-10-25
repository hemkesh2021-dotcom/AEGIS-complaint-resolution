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

## Complaint creation boundary

- Public `/api/intake` always issues a new server-generated case reference; any
  supplied `complaintId` is ignored. Generated references use a full UUID.
- Authenticated `/api/complaints` may supply a new reference for integration use.
  An existing reference is a conflict (HTTP 409), never an update or lookup.
- All creation paths use an insert-only database operation. Primary-key uniqueness
  arbitrates concurrent duplicate references; a failed creation rolls back its
  audit work and cannot replace an existing reply, tracking token, or customer data.
- Retrying public intake creates another complaint, not an idempotent replay.
  Use the returned tracking token for status; creation never returns an existing
  complaint's token based on a caller-selected reference.

## Approval and notification boundaries

- Initial replies and follow-ups both require a supervisor for CRITICAL or escalated
  cases. `force=true` overrides only the grounding check, never this role check.
- Review mutations use a database row lock and a single transaction for the case,
  message, and approval audit records. Concurrent initial approvals can commit only
  one final reply; subsequent attempts return 409 without another notification.
- Retrieval and summarization run before the lock. The locked transaction rechecks
  authorization and the preparation inputs; stale inputs return 409.
- Email and portal updates run only after a successful commit. Email audit writes
  use a separate transaction. A notification failure does not reopen the approved reply.
- Delivery remains best-effort, not exactly-once SMTP: a process crash after commit
  can lose a notification, and ambiguous SMTP failures need manual reconciliation.
  No automatic retry or durable outbox is added here. `SENT` means the reply is
  committed and available in the portal, not proof of email receipt. Follow-ups are
  distinct append operations; retrying a follow-up can create another message.

## Data protection (PII)

- **Trust boundary:** classification and embeddings run locally. Translation, drafting,
  and summarization can call an external LLM. Before those calls, best-effort redaction
  masks supported email, phone, card/account and SSN-like patterns and the supplied full
  customer name. Drafting and summarization restore `{CUSTOMER_NAME}` locally; translation
  retains the token for downstream processing. Calls without a supplied name can only
  redact identifier patterns.
- **Name matching:** literal, case-insensitive Unicode matching uses letter/number/mark
  boundaries at non-Han name edges to avoid replacing substrings of unrelated words.
  Han edges do not require word boundaries because Chinese prose normally has no spaces
  around names. Exact supplied Han names are masked even within longer phrases; this
  deliberately favors privacy over possible over-redaction, not linguistic segmentation.
- **Limits:** this is not comprehensive anonymization. Unknown or partial names, spelling
  and normalization variants, other unsegmented scripts, addresses, contextual identifiers,
  and unsupported identifier formats may remain. Validate de-identification and provider
  data-handling controls before enabling external AI for real customer data.
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

