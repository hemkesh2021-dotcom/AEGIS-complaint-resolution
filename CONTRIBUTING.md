# Contributing to AEGIS

Thanks for your interest in improving AEGIS! Contributions of all kinds are welcome — bug reports, features, docs, and tests.

## Getting started

```bash
git clone https://github.com/hemkesh2021-dotcom/AEGIS-complaint-resolution.git
cd AEGIS-complaint-resolution
cp .env.example .env          # add your NIM key (optional) and set AEGIS_API_KEY
docker compose up --build
```

- API → http://localhost:8080 · Console → http://localhost:8088 · Portal → http://localhost:8088/portal.html
- Run the evaluation harness: `python3 eval/run_eval.py`

See the [README](README.md) for architecture and endpoints.

## Development workflow

1. **Fork** the repo and create a branch: `git checkout -b feat/short-description`.
2. Make your change. Keep it focused; one logical change per PR.
3. Make sure it builds and CI would pass:
   - API: `cd api && mvn -B clean package`
   - Classifier: `cd classifier && python -m py_compile main.py`
4. Update docs/eval set if behavior changed.
5. **Commit** with a clear message and open a **Pull Request** against `main`.

The GitHub Actions CI (`.github/workflows/ci.yml`) builds and tests both services and both images on every PR — it must be green to merge.

## Code style

- **Java:** standard Spring Boot conventions; keep controllers thin and logic in services.
- **Python:** PEP 8; standard library preferred for the tooling scripts.
- **Frontend:** the console/portal are dependency-free single files — keep them that way.
- Prefer small, well-named methods; fail soft (fall back) rather than crash the pipeline.

## Reporting bugs / requesting features

Use the issue templates. For **security issues**, do **not** open a public issue — see [SECURITY.md](SECURITY.md).

## Contributor License

By contributing, you agree that your contributions are licensed under the [MIT License](LICENSE).
