import { notFound } from "next/navigation";
import { DevSplashHide } from "./DevSplashHide";

// Dev-preview routes (fixture-fed, no auth). Hard 404 in production unless
// explicitly enabled — defense in depth alongside the proxy.
export default function DevLayout({ children }: { children: React.ReactNode }) {
  if (
    process.env.NODE_ENV === "production" &&
    process.env.NEXT_PUBLIC_ENABLE_DEV_ROUTES !== "true"
  ) {
    notFound();
  }
  // In the capture build these fixtures render the shell directly (no
  // NativeAppLayout), so they must drop the held native splash themselves
  // (§F.3). No-op on web.
  return (
    <>
      <DevSplashHide />
      {children}
    </>
  );
}
