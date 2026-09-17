// Apple App Site Association — universal-link verification for the iOS app
// (capacitor-ios-spec §B.5). Served from the WEB origin (app.afterduty.app),
// which is where Apple fetches it; the native app never serves it. It maps the
// app's Associated Domains (`applinks:app.afterduty.app`) to the deep-link
// paths the app handles: share-invite acceptance (`/accept-share/*`). (The
// former `/finish-sign-in*` entry left with the magic-link flow — OTP codes are
// typed, not clicked, so sign-in needs no universal link.)
//
// A GET route handler (not a `public/` file) is used deliberately: Apple's
// validator REQUIRES `Content-Type: application/json`, and an extension-less
// `public/.well-known/*` file is served as `application/octet-stream` by the
// Next server. The handler sets the correct type and serves reliably on the
// standalone BFF. In the native export build a GET route handler renders to a
// static file (harmless and unused — the app is the consumer, not the server).

// appID = <TEAMID>.<bundle id>. Team 3ZKP4S469J is confirmed from the Xcode
// Cloud archive log (build 28) and owns BOTH app records: After Duty
// (6809038854, com.afterduty.app — the shipping app) and the retired VA Claim
// Path record (6771148030, com.vaclaimpath.app — still installed on early
// testers' phones). Both are listed so universal links keep opening whichever
// app is installed; drop the old id once that record is gone. A wrong Team ID
// makes universal links SILENTLY fall back to Safari. Overridable at deploy time
// via `APPLE_APP_SITE_ASSOCIATION_APP_ID` (comma-separated) on the BFF Cloud Run
// service, so a corrected value never needs a code change.
const APP_IDS = (
  process.env.APPLE_APP_SITE_ASSOCIATION_APP_ID ??
  "3ZKP4S469J.com.afterduty.app,3ZKP4S469J.com.vaclaimpath.app"
)
  .split(",")
  .map((s) => s.trim())
  .filter(Boolean);

const AASA = {
  applinks: {
    apps: [],
    details: [
      {
        // `appIDs` (plural) is the current AASA schema; iOS 13+ reads it.
        appIDs: APP_IDS,
        paths: ["/accept-share/*"],
      },
    ],
  },
};

export function GET(): Response {
  return new Response(JSON.stringify(AASA), {
    status: 200,
    headers: {
      "Content-Type": "application/json",
      "Cache-Control": "public, max-age=3600",
    },
  });
}
