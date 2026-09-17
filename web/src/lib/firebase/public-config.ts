// Public Firebase web config, provided at RUNTIME (not build-time inlined), so the
// Cloud Run service can set it via env without a rebuild. The root layout reads it
// server-side and injects window.__AD_FB__; the client SDK reads that.
// These values are public client config (shipped in every web client), not secrets.

export interface PublicFirebaseConfig {
  apiKey: string;
  authDomain: string;
  projectId: string;
  storageBucket: string;
  messagingSenderId: string;
  appId: string;
}

export const FB_WINDOW_KEY = "__AD_FB__";

/** Read on the server at request time (process.env is live in the Node runtime). */
export function readPublicFirebaseConfig(): PublicFirebaseConfig {
  return {
    apiKey: process.env.NEXT_PUBLIC_FIREBASE_API_KEY ?? "",
    authDomain: process.env.NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN ?? "",
    projectId: process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID ?? "",
    storageBucket: process.env.NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET ?? "",
    messagingSenderId: process.env.NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID ?? "",
    appId: process.env.NEXT_PUBLIC_FIREBASE_APP_ID ?? "",
  };
}
