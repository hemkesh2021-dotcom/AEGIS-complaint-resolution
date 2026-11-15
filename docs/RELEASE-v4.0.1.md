# AEGIS 4.0.1 - Original interface, upgraded graphics

Corrects the scope of v4.0.0: the request was to improve graphics, not redesign UI/UX.

## Restored

- Operator console, customer portal and inbox restored to their original files from commit `15a8090`.
- System map's original layout, palette, labels, navigation, phase rail and interaction controls restored.
- Removed the added themes, command palette, saved views, queue export and new graphics/provider controls.

## Retained

- Desktop rendering: 4096px shadows, 1024px environment reflections, up to 2.5x device pixel ratio, maximum supported texture anisotropy and 5,000 particles.
- Camera-facing miniature display panels and guarded postprocessing fallback. Existing bloom and FXAA retain their original appearance settings.
- Original automatic balanced profile for narrow screens/low-core devices. No new graphics settings UI.
- Backend support for Anthropic, NVIDIA NIM, OpenAI, Gemini, Groq, OpenRouter, Ollama and custom compatible endpoints. Keys remain server-side. Protected diagnostics and supervisor-only connection probing remain API endpoints; see [provider configuration](PROVIDERS.md).

## Upgrade

Rebuild the API and frontend with `docker compose up -d --build`. No schema or provider configuration change is required from v4.0.0. Hard-refresh open browser tabs to replace cached v4.0.0 assets.

## Verification and boundaries

Browser checks protect the restored UI with source fingerprints and exercise original queue filtering, example selection, desktop/mobile WebGL rendering, animation, orbit/zoom/reset, journey controls and optional-postprocessing fallback. Canvas-pixel checks establish that the rendered scene is nonblank; screenshots provide visual inspection artifacts.

All 109 Java tests passed with no failures, errors or skipped tests, and the v4.0.1 release package built successfully. Backend provider adapters use local HTTP fixtures, not paid provider calls. The full Docker stack is not locally verified because the Docker daemon is unavailable. Three.js and web fonts still depend on the existing CDN URLs. This is a GitHub software release, not a hosted production deployment or regulatory certification.
