# Contributing to After Duty

Thanks for considering a contribution. Quick orientation below.

## Before you start

- Open an issue first for non-trivial changes so we can confirm scope.
- For security issues, **do not open a public issue** — see
  [SECURITY.md](./SECURITY.md).
- Be a good neighbor — see the [Code of Conduct](./CODE_OF_CONDUCT.md).

## Licensing & sign-off (DCO)

- Contributions are accepted under the project's [Apache-2.0 license](./LICENSE).
- We require the [Developer Certificate of Origin](https://developercertificate.org/):
  certify that you wrote the change (or have the right to submit it) by adding a
  `Signed-off-by` line to every commit. Use `git commit -s` (it appends, using your
  `git config user.name` / `user.email`):

  ```
  Signed-off-by: Your Name <you@example.com>
  ```

## Branch + commit conventions

- Branch off `main`. Name your branch `<topic>/<short-purpose>` (e.g.
  `feature/add-export`, `fix/null-stripe-customer`).
- Conventional-Commit-style subjects are preferred:
  `feat:`, `fix:`, `chore:`, `docs:`, `refactor:`, `test:`.
- Keep commits focused. Rebase noisy work-in-progress before opening a PR.

## Pull requests

- Fill out the PR template (summary + test plan).
- Link the issue you're closing.
- Make sure CI is green before requesting review.
- Squash-merge is the default — keep history linear on `main`.

## Running the test suites

```bash
# Backend
cd spring-backend && ./gradlew test                    # full
cd spring-backend && ./gradlew test -PregressionOnly   # tagged regression

# Frontend
cd web && npm test                                     # full (vitest)
cd web && npm run test:e2e                             # end-to-end (Playwright)
```

If you add a new feature, tag its tests so the pre-deploy regression sweep
picks them up:

- Backend: `@Tag("regression")` at the class level.
- Web: regression tests live in the vitest suite (no tag system — the whole
  suite runs).

## Local development

See the quick-start in [README.md](./README.md). You'll need to provide your
own Firebase project, Stripe test keys, and (optionally) Anthropic / Google
Vertex credentials for full functionality. The backend will fall back to H2
in-memory if no Postgres is configured.

## Code style

- **Java**: follow the existing Spring Boot conventions in the codebase.
- **TypeScript/React**: `cd web && npm run lint && npm run typecheck` should
  report zero issues before opening a PR.
- Tests live next to the code they exercise — no separate `tests/`
  directory inside the module.

## What we will and won't accept

We're happy to take:
- Bug fixes with a regression test.
- Documentation improvements.
- New features that align with the stated product spec in the README.
- Accessibility and i18n improvements.

We'll usually push back on:
- Large rewrites without prior discussion in an issue.
- Changes that add new third-party services or paid dependencies.
- New AI/LLM providers added in addition to (rather than replacing) the
  existing ones.
