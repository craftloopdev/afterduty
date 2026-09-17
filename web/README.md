# After Duty — web frontend

Next.js BFF serving app.afterduty.app (Cloud Run service `va-claim-web-next`)
and, via static export + Capacitor, the iOS and Android apps.

Agent/contributor conventions live in [AGENTS.md](AGENTS.md). Architecture and
platform notes: `docs/architecture/` at the repo root.

## Commands

```bash
npm run dev             # local dev server
npm test                # vitest suite
npm run lint            # eslint
npm run typecheck       # tsc --noEmit
npm run build           # web (Cloud Run) build
npm run build:native    # static export for the Capacitor shells
npm run cap:sync        # export + sync into ios/ (android: npx cap sync android)
```

## Deploy

Web ships from this directory via Cloud Run source deploy
(`gcloud run deploy va-claim-web-next --source .`). iOS builds through
Xcode Cloud from `web/ios`; Android from `web/android` with Gradle.
