# Local graphics preview

Start Docker Desktop, then run from the repository root:

```bash
bash infra/prepare-preview.sh
docker compose -f docker-compose.yml -f compose.local.yml up -d --build
```

This preview binds exposed services to `127.0.0.1`, preserves the existing database volume and forces the API into template mode. No paid model calls are made. The preparation script downloads the pinned MiniLM ONNX model (about 90 MB) into gitignored `.local/embedding` and verifies its SHA-256 against the Git LFS digest shipped in Spring AI 1.0.9. The API mounts it read-only and uses the tokenizer already bundled in its dependency, avoiding remote model downloads inside Docker at startup. The regular deployment configuration and `.env` are not modified. Use a Docker Compose version supporting `!override`; validate with `docker compose -f docker-compose.yml -f compose.local.yml config --quiet`.

| Surface | Local URL |
|---|---|
| Operator console | http://localhost:8088/index.html |
| 3D architecture | http://localhost:8088/architecture.html |
| Customer portal | http://localhost:8088/portal.html |
| AEGIS Mail | http://localhost:8088/inbox.html |
| API health | http://localhost:8080/health |
| Identity provider | http://localhost:8089 |

Use your existing operator API key from `.env` in the console's original sidebar field. Provider keys belong only on the server, not in this field. The local Keycloak realm also retains its original demo accounts. Never expose these demo credentials or services on a public network.

## Visual scope

The original HTML and business logic remain in the operator console, portal and inbox. Two asset references add an isolated `motion.css` / `motion.js` layer. Desktop layouts, fonts, colors, navigation and existing controls stay intact. The layer adds restrained raised surfaces, recessed fields, button bevels, selected-row detail, keyboard focus outlines and short transitions. Confidence bars and acknowledgement checks animate into their real final values; animations never delay an action. Mail refreshes no longer replay entry effects for messages already seen.

Approved mobile improvements keep the same workflows: below 641px the mail folders sit above a full-width message list; the overlay reader has a local Lucide back icon and a touch-friendly back button below 1001px. While the reader is open, background controls are inert and keyboard focus stays in the reader; closing returns focus to the selected message. Mail rows also support Enter and Space. Long references wrap inside their existing surfaces, and narrow console tables scroll within the queue instead of stretching the page. Very narrow acknowledgements stack each label above its value. No records, API settings or provider behavior change.

The 3D view retains its original palette, component locations, label text and controls. The Neural Classifier now uses a matte processor with stylized neural connections and 11 output nodes, replacing the overexposed sphere and reflective base. Its small moving signals illustrate classification; they are not live model activations. The classifier caption sits higher to keep the network visible at elevated camera angles. Rendering also includes double-resolution desktop screen/label textures, mipmapping, the existing 4096px desktop shadows and 1024px reflections, frame-rate-independent camera damping, smoothed parallax, flowing signal trails and responsive scene framing. Colliding scene labels are suppressed until space is available or their component is hovered; component picking and tooltips remain available. Narrow screens keep the original balanced rendering profile.

System reduced-motion preferences disable the added interface animation and idle 3D motion, including when the preference changes with the page open. Background tabs pause the 3D render loop. Expired signal-pulse GPU resources are disposed. `?motion=off` disables the added surface finish and interface feedback for comparison; responsive containment, keyboard access and the mobile inbox fix remain active. It does not revert the renderer or the original page's pre-existing effects.

## Feature status

New in v4: server-side Anthropic, NIM, OpenAI, Gemini, Groq, OpenRouter, Ollama and custom compatible model adapters; protected provider diagnostics; a supervisor-only connection probe; credential isolation and template fallback. See [provider setup](PROVIDERS.md).

The original approval, escalation, SLA tracking, insights, similar cases, live customer status, follow-up thread, audit and email workflows remain. New queue searches, saved views, a command palette and theme controls are **not** included: they were removed with the rejected interface redesign.

## Verification

```bash
cd frontend
npm ci
npx playwright install chromium
npm test
```

The browser suite checks original source fingerprints after removing only the two motion-asset references. It compares desktop positions, sizes, text, fonts, colors and corner radii with the visual layer loaded versus blocked. Mobile checks retain the portal layout and console typography/controls, with explicit exceptions for the responsive fixes described above. Intercepted fixtures cover console editing, submission and failure recovery, tracking and follow-ups, mail filtering/reading, keyboard and touch return, SVG loading, non-repeating mail refresh effects and long-content containment at 1440px, 390px and 320px. No real case is submitted and no email is sent by these tests.

Rendering checks cover canvas pixels, moving frames, label collisions, orbit/zoom/reset, journey controls, native-rendering fallback and live reduced-motion changes. These are interface and rendering checks, not a production capacity or accessibility certification.

To stop the preview without removing data:

```bash
docker compose -f docker-compose.yml -f compose.local.yml stop
```
