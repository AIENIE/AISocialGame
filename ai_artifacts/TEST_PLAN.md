# AISocialGame Dev-Test Plan

## Goal
Run the configured deterministic checks first. Run acceptance checks only when explicitly enabled.

## Failure Classification
- Missing credentials, Docker, sudo, ports, local domains, browsers, or external services are `env_issue`.
- Only clear P0/P1 `product_bug` findings may be repaired automatically.
- Test-agent writes are limited to `ai_artifacts/` and `.codex/dev-test/runs/`.

## Notes
- Use pnpm for frontend commands.

