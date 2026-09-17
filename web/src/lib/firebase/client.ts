"use client";

import { initializeApp, getApps, getApp, type FirebaseApp } from "firebase/app";
import { getAuth, type Auth } from "firebase/auth";
import { FB_WINDOW_KEY, type PublicFirebaseConfig } from "./public-config";

// Lazy singletons so nothing touches the Firebase SDK during SSR import.
let app: FirebaseApp | undefined;
let auth: Auth | undefined;

function runtimeConfig(): PublicFirebaseConfig {
  const cfg = (globalThis as unknown as Record<string, PublicFirebaseConfig | undefined>)[
    FB_WINDOW_KEY
  ];
  if (!cfg?.apiKey) {
    throw new Error("Firebase config missing — window." + FB_WINDOW_KEY + " not injected");
  }
  return cfg;
}

export function getFirebaseApp(): FirebaseApp {
  if (!app) {
    const cfg = { ...runtimeConfig() };
    // Use the app's own origin as the auth handler in prod so OAuth (Google/Apple)
    // runs same-origin and isn't blocked by Safari ITP. /__/auth/* is proxied to
    // Firebase via next.config rewrites. Local dev keeps the firebaseapp.com
    // authDomain (desktop popups work there).
    if (typeof window !== "undefined" && window.location.hostname !== "localhost") {
      cfg.authDomain = window.location.host;
    }
    app = getApps().length ? getApp() : initializeApp(cfg);
  }
  return app;
}

export function getFirebaseAuth(): Auth {
  if (!auth) auth = getAuth(getFirebaseApp());
  return auth;
}
