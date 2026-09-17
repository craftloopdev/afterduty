"use client";

// AuthDriver selector (capacitor-ios-spec §B.2). The build-time `NATIVE`
// constant picks the implementation so the UNSELECTED driver — and its imports —
// is eliminated by the bundler. On web this means the Capacitor plugin imports in
// `driver.native.ts` never enter the web bundle; on native the cookie/popup
// `driver.web.ts` is dropped. Components import `authDriver` from here and stay
// target-agnostic.
//
// Note: the spec describes selecting via `Capacitor.isNativePlatform()` (a
// runtime check). We use the build-time `NATIVE` constant instead for the same
// outcome with a stronger guarantee — the wrong driver's plugin/popup code is
// statically tree-shaken rather than merely skipped at runtime, so neither
// bundle carries the other's dependencies.

import { NATIVE } from "@/lib/platform";
import type { AuthDriver } from "./driver";
import { webAuthDriver } from "./driver.web";

let driver: AuthDriver = webAuthDriver;

if (NATIVE) {
  // Static require kept inside the dead-on-web branch so the web bundle never
  // pulls the native plugin graph. Resolved synchronously at module load on
  // native, where the constant is statically true.
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  driver = (require("./driver.native") as typeof import("./driver.native")).nativeAuthDriver;
}

export const authDriver: AuthDriver = driver;
export type {
  AuthDriver,
  AccountDetails,
  AuthStatus,
  PasskeyCredential,
  PhoneConfirmation,
  VerifyEmailResult,
} from "./driver";
