# Codex Dev-Test

This repository is wired into the local multi-model dev-test loop through `codex-dev-test`.

- Default checks: backend Maven tests, frontend build, and frontend lint.
- Playwright real/acceptance flows are opt-in with `--acceptance`.
- Test-agent writes are limited to `ai_artifacts/` and `.codex/dev-test/runs/`.
- `env.local`, `testuser.txt`, and runtime logs are protected local state.

Example:

```bash
codex-dev-test test /home/duwei/aienie-projects/AISocialGame --dry-run
codex-dev-test test /home/duwei/aienie-projects/AISocialGame
```
