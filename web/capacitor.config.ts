import type { CapacitorConfig } from "@capacitor/cli";

// Capacitor config (capacitor-ios-spec §D.2, decisions D5/D7). As of the After
// Duty rebrand (Stage 2) this ships as a NEW store listing on com.afterduty.app
// for both platforms — the old iOS listing 6771148030 (com.vaclaimpath.app) is
// retired once the new app is approved, and the never-published Play draft on
// com.vaclaimpath.app is abandoned. Nothing carries over: new IAPs, new
// RevenueCat apps, no existing subscribers to migrate.
//   - webDir "out"            → the static export produced by `npm run build:native`.
//   - server.iosScheme https  → WebView origin https://localhost (in Firebase's
//                               default authorized domains; friendlier to
//                               IndexedDB/localStorage than capacitor://).
//   - SplashScreen launchAutoHide:false → held until the NativeAuthGate resolves
//                               (no white flash / content pop — §A.4/§H.5).
//   - Keyboard resize:"native" → the chat composer / login inputs scroll into
//                               view under WKWebView (§D.3).
const config: CapacitorConfig = {
  appId: "com.afterduty.app",
  appName: "After Duty",
  webDir: "out",
  server: {
    iosScheme: "https",
  },
  plugins: {
    SplashScreen: {
      launchAutoHide: false,
      // Match the launch storyboard + the splash image's ground (#1B2A4A, the
      // app-icon navy) so the held splash view never flashes a different color.
      backgroundColor: "#1b2a4a",
    },
    Keyboard: {
      resize: "native",
    },
    FirebaseAuthentication: {
      // Native-layer sign-in is REQUIRED (§B.1): the keychain ID token is the
      // DirectApiClient's Bearer source, and the plugin rejects custom-token
      // sign-in when skipNativeAuth is true.
      skipNativeAuth: false,
      // Pure passwordless OTP (§B.2): phone is the only provider the plugin
      // loads. Email codes ride a backend custom token — no provider needed.
      // No Google/Apple — with no third-party login, guideline 4.8 (Sign in
      // with Apple) does not apply.
      providers: ["phone"],
    },
  },
};

export default config;
