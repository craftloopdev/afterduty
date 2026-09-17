"use client";

import { ThemeProvider } from "@/components/shell/ThemeProvider";
import LoginPage from "@/app/(auth)/login/page";
import authStyles from "@/app/(auth)/auth.module.css";

// Dev preview for the rebuilt single-box passwordless login
// (passwordless-otp-auth-spec §9). Renders the REAL LoginPage inside the same
// (auth) card shell so the top→bottom layout (shield icon, title, subtitle, one
// "Phone or email" box, the passwordless instruction line, "Send me a code")
// can be eyeballed without a session.
//
// This is a fixture-free render: LoginPage is a self-contained client component
// (it talks to Firebase + the email-code BFF on interaction), so there are no
// variants to seed — interacting here would hit the live backend, exactly like
// the real page. Use it for visual/layout review only. Dev routes 404 in
// production (handled by dev/layout.tsx).
export default function DevLoginPage() {
  return (
    <ThemeProvider>
      <main className={authStyles.shell}>
        <div className={authStyles.card}>
          <LoginPage />
        </div>
      </main>
    </ThemeProvider>
  );
}
