import type { Metadata, Viewport } from "next";
import { Inter_Tight, JetBrains_Mono } from "next/font/google";
import "./globals.css";
import { readThemePrefs, DEFAULT_PREFS } from "@/lib/theme/prefs";
import { readPublicFirebaseConfig, FB_WINDOW_KEY } from "@/lib/firebase/public-config";
import { NATIVE } from "@/lib/platform";
import { ThemeBoot } from "@/components/native/ThemeBoot";
import { CaptureDeepLinks } from "@/components/native/CaptureDeepLinks";

const interTight = Inter_Tight({
  subsets: ["latin"],
  display: "swap",
  variable: "--font-inter-tight",
});

const jetbrainsMono = JetBrains_Mono({
  subsets: ["latin"],
  display: "swap",
  variable: "--font-jetbrains-mono",
});

export const metadata: Metadata = {
  title: "After Duty",
  description: "Organize your evidence, understand your claim, and move forward.",
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  themeColor: "#1B2A4A",
  // Required for safe-area env() vars under WKWebView; harmless on web (§A.6).
  viewportFit: "cover",
};

export default async function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  // NATIVE (static export): `readThemePrefs()` calls `cookies()`, which would
  // make the export build dynamic and fail. Render with DEFAULT_PREFS and let the
  // client `<ThemeBoot>` apply persisted prefs before content paints (the splash
  // covers the swap — §A.6). WEB keeps the server cookie read for no-FOUC SSR.
  const { theme, scale, dark } = NATIVE ? DEFAULT_PREFS : await readThemePrefs();
  const firebase = readPublicFirebaseConfig();
  return (
    <html
      lang="en"
      data-theme={theme}
      data-scale={scale}
      data-scroll-behavior="smooth"
      // Marks the native WebView so the token CSS layer can guard safe-area
      // padding (`:root[data-native]`) without touching the web layout (§D.3).
      {...(NATIVE ? { "data-native": "" } : {})}
      className={`${interTight.variable} ${jetbrainsMono.variable}${dark ? " ad-dark" : ""}`}
    >
      <body>
        <script
          // Public Firebase web config for the client SDK. On web this is the
          // runtime-injected value (Cloud Run env, no rebuild); in the export
          // build `readPublicFirebaseConfig()` runs at BUILD time and inlines the
          // same public client values into the static HTML (§A.1) — fine, they
          // ship in every client anyway.
          dangerouslySetInnerHTML={{
            // Trusted server config; escape "<" so no value can break out of <script>.
            __html: `window.${FB_WINDOW_KEY}=${JSON.stringify(firebase).replace(/</g, "\\u003c")};`,
          }}
        />
        {NATIVE ? <ThemeBoot /> : null}
        {/* Capture builds only (compiles to null otherwise): resolves the screenshot
            pipeline's /dev/* deep links from any screen, incl. the cold-launch URL. */}
        {NATIVE ? <CaptureDeepLinks /> : null}
        {children}
      </body>
    </html>
  );
}
