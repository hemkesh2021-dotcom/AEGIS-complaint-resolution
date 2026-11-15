# AEGIS 4 product research

Research date: 15 November 2026. This is a focused product-direction review, not a market-size study or independent validation of vendor claims. Exa returned 16 results across three searches: YC support products, provider compatibility, and operational inbox workflows. Relevant official sources informed the decisions below.

| Source | Observed direction | Decision for AEGIS |
|---|---|---|
| [Letterbook / YC](https://www.ycombinator.com/companies/letterbook) | Review-and-send workflows and fast navigation | Keep human approval; add a command palette |
| [Typewise / YC](https://www.ycombinator.com/companies/typewise) | Explicit automation boundaries and human handoffs | Preserve the existing approval and escalation controls |
| [Intryc / YC](https://www.ycombinator.com/companies/intryc) | Review quality, monitoring and operational visibility | Expose provider failure counts; retain audit and existing insights |
| [Intercom custom views](https://www.intercom.com/help/en/articles/6588834-organize-your-inbox-with-custom-views-and-folders) | Repeatable views of urgent work | Add personal saved searches/filters/sorts |
| [Intercom SLAs](https://www.intercom.com/help/en/articles/6546152-set-slas-for-conversations-and-tickets) | Deadline visibility and sorting | Add overdue queue filter and deadline sorting |
| [Anthropic Messages](https://platform.claude.com/docs/en/api/messages/create) | Native Messages protocol differs from compatible endpoints | Use a dedicated request/response branch, not an OpenAI URL substitution |
| [Groq compatibility](https://console.groq.com/docs/openai), [Gemini compatibility](https://aistudio.google.com/docs/openai), [OpenRouter compatibility](https://openrouter.ai/docs/guides/features/compatibility) | Common chat-completion shape with provider-specific keys/bases | Configure a single destination and isolate credentials |

Sider Scholar located *Generative AI at Work* by Erik Brynjolfsson, Danielle Li and Lindsey Raymond ([paper](https://arxiv.org/abs/2304.11771)). This release does not claim the paper's productivity findings transfer to AEGIS; no new user productivity experiment was run.

Firecrawl retrieved the Anthropic API documentation. Mobbin's search was blocked by its paid-plan requirement, so no Mobbin screenshots were used or recreated. The [NVIDIA skill catalog](https://raw.githubusercontent.com/NVIDIA/skills/main/skills.sh.json) was checked; a browser Three.js renderer does not justify installing an Omniverse/GPU deployment stack. NIM remains supported through the provider adapter. No new NVIDIA skill was installed.

The implementation uses the owner's requested minimalist neumorphism with light/dark themes. The 3D map is an optional architecture view; the default screen stays focused on complaint review.

## Deliberate scope

Saved views persist only in the current browser and search only the latest 50 loaded cases. They are not shared team views or full-history database search. CSV export excludes narratives and tracking tokens. Metrics describe the loaded queue, not the entire case history. Provider counters reset on restart and are not billing/usage accounting. Tenant isolation, attachments, omnichannel connectors, team assignment and durable provider metering require separate designs and are not advertised as shipped features.
