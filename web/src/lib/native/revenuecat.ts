// RevenueCat facade (capacitor-ios-spec §C). The ONLY module that imports the
// `@revenuecat/purchases-capacitor` plugin — every payment action on native goes
// through here. Mirrors the splash/deep-links/haptics facade pattern: the plugin
// import is lazy + guarded by the build-time `NATIVE` constant, so it is fully
// tree-shaken out of the web bundle (which keeps Stripe and never loads RC).
//
// App-user-id is the Firebase UID (matches the backend's `subscriber.firebase_uid`
// lookup — §C.1). Entitlement id is the spec's `After Duty Pro` (must match the
// Cloud Run `REVENUECAT_ENTITLEMENT_ID` env — §C.2). Products `pro_monthly`/
// `pro_annual` live in offering `default` (`$rc_monthly`/`$rc_annual`).
//
// Prices come from StoreKit via the package's `product.priceString` — NEVER a
// literal — so the paywall and the Apple purchase sheet can never diverge (the
// v1.0 Guideline-3 price query, §C.3/§H.1).

import { Capacitor } from "@capacitor/core";
import { NATIVE } from "@/lib/platform";

// The RC SDK public keys are build-time PUBLIC values (safe to embed — §A.1).
// One bundle serves both stores, so the correct per-store key is picked at
// runtime by platform: Apple (`appl_…`) on iOS, Google (`goog_…`) on Android.
const RC_IOS_KEY = process.env.NEXT_PUBLIC_REVENUECAT_IOS_KEY ?? "";
const RC_ANDROID_KEY = process.env.NEXT_PUBLIC_REVENUECAT_ANDROID_KEY ?? "";

/** The RevenueCat SDK key for the running store (empty on web / when unset). */
function rcApiKey(): string {
  return Capacitor.getPlatform() === "android" ? RC_ANDROID_KEY : RC_IOS_KEY;
}

/** Pro entitlement identifier — must match `REVENUECAT_ENTITLEMENT_ID` on the
 *  backend (§C.2/risk-table). Read from a constant so native code and the backend
 *  agree on the exact key. */
export const PRO_ENTITLEMENT_ID = "After Duty Pro";

// Minimal structural mirrors of the RC plugin types we consume. We avoid a hard
// type-import of the plugin at module scope so this file type-checks on the web
// target too (the plugin's types are still installed, but keeping the surface
// local documents exactly what the paywall relies on and survives plugin minor
// churn). The runtime values come straight from the plugin.
export interface RcPlan {
  /** RC package identifier (e.g. `$rc_monthly`). */
  identifier: string;
  /** StoreKit product id (`pro_monthly` / `pro_annual`). */
  productId: string;
  /** StoreKit-localized price, e.g. "$11.99" — render this, never a literal. */
  priceString: string;
  /** "month" | "year" derived from the package type, for the disclosure copy. */
  period: "month" | "year";
  /** The raw RC package, passed back to `purchase()`. */
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  pkg: any;
}

/** Local mirror of the bits of CustomerInfo the paywall oracle reads (§C.4). */
export interface RcEntitlement {
  active: boolean;
  /** Store the entitlement was unlocked from (e.g. "APP_STORE"). */
  store: string | null;
  expiresAt: string | null;
}

/** Lazy plugin handle — only resolved on native, never imported on web. */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function plugin(): any {
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  return (require("@revenuecat/purchases-capacitor") as { Purchases: unknown }).Purchases;
}

let configured = false;

/** Configure the SDK once at app boot (native only). Safe to call repeatedly. */
export async function configureRevenueCat(): Promise<void> {
  const apiKey = rcApiKey();
  if (!NATIVE || configured || !apiKey) return;
  await plugin().configure({ apiKey });
  configured = true;
}

/** Associate the RC subscriber with the Firebase UID (§C.1) — called on auth
 *  resolution so receipts validate against the right `subscriber.firebase_uid`. */
export async function rcLogIn(firebaseUid: string): Promise<void> {
  if (!NATIVE || !configured || !firebaseUid) return;
  await plugin().logIn({ appUserID: firebaseUid });
}

/** Reset the RC subscriber to an anonymous id (§C.1) — on sign-out / deletion. */
export async function rcLogOut(): Promise<void> {
  if (!NATIVE || !configured) return;
  // logOut throws if already anonymous; swallow so sign-out never wedges.
  await plugin()
    .logOut()
    .catch(() => {});
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function toPlan(pkg: any): RcPlan {
  const product = pkg?.product ?? {};
  // PACKAGE_TYPE.ANNUAL === "ANNUAL"; default everything else to monthly copy.
  const period: "month" | "year" =
    String(pkg?.packageType ?? "").toUpperCase() === "ANNUAL" ? "year" : "month";
  return {
    identifier: String(pkg?.identifier ?? ""),
    productId: String(product?.identifier ?? ""),
    priceString: String(product?.priceString ?? ""),
    period,
    pkg,
  };
}

/** Fetch the StoreKit-priced plans from the `default` offering (§C.3). Returns
 *  the available packages mapped to `RcPlan`; empty when offerings are missing. */
export async function rcGetPlans(): Promise<RcPlan[]> {
  if (!NATIVE) return [];
  const offerings = await plugin().getOfferings();
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const current = offerings?.current as any;
  const pkgs = (current?.availablePackages ?? []) as unknown[];
  return pkgs.map(toPlan);
}

/** Read the cached entitlement (§C.4 oracle). Returns the `After Duty Pro`
 *  entitlement if present (active or not), else null. Never throws — a failure
 *  collapses to null so the caller falls back to the backend status. */
export async function rcGetEntitlement(): Promise<RcEntitlement | null> {
  if (!NATIVE) return null;
  try {
    const info = await plugin().getCustomerInfo();
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const ent = info?.customerInfo?.entitlements?.all?.[PRO_ENTITLEMENT_ID] as any;
    if (!ent) return null;
    return {
      active: Boolean(ent.isActive),
      store: ent.store ?? null,
      expiresAt: ent.expirationDate ?? null,
    };
  } catch {
    return null;
  }
}

/** Outcome of a purchase/restore attempt. `cancelled` is the user dismissing the
 *  StoreKit sheet (RC `userCancelled`) — a silent reset, not an error (§C.3). */
export type RcPurchaseOutcome =
  | { kind: "purchased" }
  | { kind: "cancelled" }
  | { kind: "error"; message: string };

/** Purchase a package via StoreKit (§C.3). On success the caller posts
 *  `/subscription/revenuecat/sync` then refetches status. */
export async function rcPurchase(plan: RcPlan): Promise<RcPurchaseOutcome> {
  if (!NATIVE) return { kind: "error", message: "Purchases are unavailable here." };
  try {
    await plugin().purchasePackage({ aPackage: plan.pkg });
    return { kind: "purchased" };
  } catch (e) {
    if (isUserCancelled(e)) return { kind: "cancelled" };
    return { kind: "error", message: errMessage(e) };
  }
}

/** Restore prior purchases (MANDATORY for App Review — §C.3/§H.4). On success the
 *  caller syncs + refetches; whether an entitlement was actually recovered is
 *  read back from the refreshed status. */
export async function rcRestore(): Promise<RcPurchaseOutcome> {
  if (!NATIVE) return { kind: "error", message: "Restore is unavailable here." };
  try {
    await plugin().restorePurchases();
    return { kind: "purchased" };
  } catch (e) {
    return { kind: "error", message: errMessage(e) };
  }
}

/** Open the native StoreKit management sheet (§C.3/§C.5). The Capacitor plugin
 *  may not expose `showManageSubscriptions`; fall back to the App Store account
 *  subscriptions URL opened externally. Never the Stripe portal on iOS. */
export async function rcManageSubscriptions(): Promise<void> {
  if (!NATIVE) return;
  const p = plugin();
  if (typeof p.showManageSubscriptions === "function") {
    await p.showManageSubscriptions().catch(() => openAppleSubscriptions());
    return;
  }
  openAppleSubscriptions();
}

function openAppleSubscriptions(): void {
  if (typeof window !== "undefined") {
    window.open("https://apps.apple.com/account/subscriptions", "_blank");
  }
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function isUserCancelled(e: any): boolean {
  // RC surfaces `userCancelled: true` and code "1" (PURCHASE_CANCELLED_ERROR).
  return Boolean(e?.userCancelled) || String(e?.code) === "1";
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function errMessage(e: any): string {
  return typeof e?.message === "string" && e.message ? e.message : "Something went wrong.";
}
