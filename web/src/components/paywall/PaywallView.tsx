"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/Button";
import { Icon } from "@/components/ui/Icon";
import { Pill } from "@/components/ui/Pill";
import { tierLabel, formatLongDate } from "@/lib/format";
import { syncRevenueCat } from "@/lib/api/mutations";
import {
  rcGetPlans,
  rcGetEntitlement,
  rcPurchase,
  rcRestore,
  rcManageSubscriptions,
  type RcPlan,
} from "@/lib/native/revenuecat";
import { tapLight, notifySuccess, notifyError } from "@/lib/native/haptics";
import { EULA_URL, PRIVACY_URL } from "@/lib/constants";
import type { CheckoutOutcome } from "@/lib/checkout-return";
import type { SubscriptionVM, UsageBreakdownVM } from "@/lib/models/vm";
import { UsageBreakdown } from "@/components/usage/UsageBreakdown";
import {
  FeaturesList,
  FreeForeverCard,
  MissionNote,
  PAYWALL_HERO_SUB,
  PAYWALL_HERO_TITLE,
} from "./PaywallParts";
import type { StripePaywall as StripePaywallType } from "./StripePaywall";
import styles from "./PaywallView.module.css";

/** Re-export so existing callers can keep importing the type from here. */
export type { CheckoutOutcome };

/**
 * The paywall. On WEB (default) it drives Stripe checkout/portal exactly as
 * before — byte-identical. On NATIVE (`native` + `onRefetch` supplied by
 * `NativeUpgrade`) it renders the RevenueCat/StoreKit branch (§C.3): StoreKit-
 * localized prices, a mandatory Restore button, the auto-renewal disclosure block
 * with functional EULA + Privacy links, and ZERO Stripe strings/flows.
 *
 * The Stripe branch lives in its own `./StripePaywall` module (mirroring
 * `WebBillingManage`), loaded by a `require` INSIDE the dead `!NATIVE` branch so
 * this file carries NO Stripe import at all — on the native build the inlined
 * `NEXT_PUBLIC_NATIVE` literal makes the require unreachable and webpack DCEs the
 * whole Stripe module (and its "/api/subscription" / "billing portal" strings)
 * out of the export. The §H.4 anti-steering audit greps `web/out/` for exactly
 * those strings; this keeps it at zero matches even across refactors.
 */
export function PaywallView({
  sub,
  usage = null,
  checkout = null,
  native = false,
  onRefetch,
}: {
  sub: SubscriptionVM;
  /** Monthly AI-spend breakdown for the "analyze my usage" card (web, Pro). */
  usage?: UsageBreakdownVM | null;
  checkout?: CheckoutOutcome;
  /** Render the RevenueCat branch instead of Stripe (native build — §C.3). */
  native?: boolean;
  /** Re-run the subscription loader after a purchase/restore (native — there is
   *  no `router.refresh()`; `NativeUpgrade`'s `useLoader.refetch`). */
  onRefetch?: () => void;
}) {
  // Native build: only the RevenueCat branch exists; the Stripe require below is
  // statically dead (the env literal folds `!== "1"` to false) and removed.
  if (process.env.NEXT_PUBLIC_NATIVE === "1") {
    return <NativePaywall sub={sub} onRefetch={onRefetch} />;
  }
  // Web build (and the native runtime prop, which never reaches here on the
  // native bundle): the prop selects native-vs-Stripe at render.
  if (native) return <NativePaywall sub={sub} onRefetch={onRefetch} />;
  // Aliased `require` inside the dead `!NATIVE` branch (the exact idiom
  // `lib/web-only/stripe-actions` uses): the inlined env literal lets webpack DCE
  // this whole branch — and the StripePaywall module — out of the native export,
  // while the `@/` alias resolves under both webpack and vitest.
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const { StripePaywall } = require("@/components/paywall/StripePaywall") as {
    StripePaywall: typeof StripePaywallType;
  };
  return (
    <>
      {/* Pro veterans see how this month's AI budget was spent, above the
          plan/manage card. Free users haven't spent AI — keep their sales
          paywall clean (the card renders nothing when there's no usage). */}
      {sub.active && <UsageBreakdown data={usage} />}
      <StripePaywall sub={sub} checkout={checkout} />
    </>
  );
}

// ───────────────────────────── Native (RevenueCat) ─────────────────────────────
// The iOS paywall (§C.3/§C.4/§H.4). Commerce is StoreKit-only — NO Stripe code is
// reachable here. Prices come from `rcGetPlans()` (StoreKit-localized) and the
// disclosure block interpolates them, never literals. The Restore button and the
// disclosure + legal links below the plan cards are App-Review-mandatory furniture.

/** Pricing oracle for the native paywall (§C.4). The backend tri-state from
 *  `getSubscriptionResult` is authoritative for free/pro, but a backend OUTAGE
 *  ("error") is resolved against RC's local `getCustomerInfo` cache: an active
 *  entitlement upgrades error→pro (show ActiveCard); no entitlement makes it safe
 *  to show purchase buttons (StoreKit dedupes at the store). error→free is NEVER
 *  produced — we never flip a real subscriber to the sales paywall. */
type NativeView = "pro" | "free" | "error-pending";

function NativePaywall({
  sub,
  onRefetch,
}: {
  sub: SubscriptionVM;
  onRefetch?: () => void;
}) {
  const [plans, setPlans] = useState<RcPlan[]>([]);
  const [busy, setBusy] = useState<string | null>(null); // plan id | "restore"
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  // The RC entitlement oracle result (§C.4): `null` until the async cache read
  // resolves, then `true`/`false` whether `After Duty Pro` is active locally.
  // The view is DERIVED from `sub.state` + this during render (no setState in an
  // effect) so a cascading render can't happen.
  const [rcActive, setRcActive] = useState<boolean | null>(null);

  // Load StoreKit plans once; the disclosure block needs their localized prices.
  useEffect(() => {
    let live = true;
    rcGetPlans()
      .then((p) => live && setPlans(p))
      .catch(() => {});
    return () => {
      live = false;
    };
  }, []);

  // Consult the RC entitlement cache when the loader resolves (mount + refetch).
  // Only needed to (a) decide Pro-vs-paywall when the BACKEND read failed, and
  // (b) decide whether to offer the native "Manage" sheet (store-bought only).
  useEffect(() => {
    let live = true;
    // Note: no synchronous reset to `null` here (that would be a setState-in-effect
    // cascading render). The async result overwrites `rcActive` when it lands; the
    // initial `null` already yields "error-pending" on a fresh error read, and a
    // refetch only ever transitions pro↔free (where the oracle doesn't drive the
    // view), so a momentarily-stale flag is invisible.
    rcGetEntitlement().then((e) => live && setRcActive(Boolean(e?.active)));
    return () => {
      live = false;
    };
  }, [sub.state]);

  // Derived view (§C.4): backend truth is authoritative for pro/free; a backend
  // OUTAGE ("error") is resolved against the RC oracle — active → Pro (no
  // double-charge bait), inactive/unknown → safe to show purchase buttons (and
  // while the oracle is still resolving on an error read, hold "error-pending").
  const view: NativeView =
    sub.state === "pro"
      ? "pro"
      : sub.state === "free"
        ? "free"
        : rcActive === null
          ? "error-pending"
          : rcActive
            ? "pro"
            : "free";
  // The native "Manage" sheet is offered only for a store-bought sub (RC says the
  // entitlement is active locally) — a web/Stripe subscriber gets no manage UI.
  const storeManaged = rcActive === true;

  const monthly = plans.find((p) => p.period === "month");
  const annual = plans.find((p) => p.period === "year");

  async function subscribe(plan: RcPlan) {
    if (busy) return;
    tapLight();
    setBusy(plan.identifier);
    setError(null);
    setInfo(null);
    const outcome = await rcPurchase(plan);
    if (outcome.kind === "cancelled") {
      setBusy(null); // silent reset (§C.3)
      return;
    }
    if (outcome.kind === "error") {
      notifyError();
      setError(outcome.message);
      setBusy(null);
      return;
    }
    notifySuccess();
    // Flip the backend immediately, then refetch so ActiveCard appears (§C.3).
    await syncRevenueCat();
    onRefetch?.();
    setBusy(null);
  }

  async function restore() {
    if (busy) return;
    tapLight();
    setBusy("restore");
    setError(null);
    setInfo(null);
    const outcome = await rcRestore();
    if (outcome.kind === "error") {
      notifyError();
      setError(outcome.message);
      setBusy(null);
      return;
    }
    await syncRevenueCat();
    // Whether anything was actually recovered shows up in the refetched status;
    // if not, leave a gentle note so the tap isn't a silent no-op.
    onRefetch?.();
    setInfo("Restored. If you have an active subscription, it'll appear shortly.");
    setBusy(null);
  }

  if (view === "error-pending") {
    // Backend status read failed and the RC oracle hasn't resolved yet — show a
    // neutral loading hint rather than flashing the sales paywall at a possible
    // subscriber (§C.4 double-charge guard).
    return (
      <div className={styles.wrap}>
        <div className={styles.card}>
          <p className={styles.heroSub}>Checking your subscription&hellip;</p>
        </div>
      </div>
    );
  }

  if (view === "pro") {
    return (
      <div className={styles.wrap}>
        <NativeActiveCard
          sub={sub}
          canManage={storeManaged}
          onManage={() => {
            tapLight();
            void rcManageSubscriptions();
          }}
        />
        {error && (
          <div className={styles.error} role="alert">
            {error}
          </div>
        )}
      </div>
    );
  }

  return (
    <div className={styles.wrap}>
      <header className={styles.hero}>
        <span className={styles.heroChip} aria-hidden="true">
          <Icon name="sparkle" size={22} stroke={2.1} />
        </span>
        {/* §6.5 outcome language — shared constants with the Stripe branch so
            the two paywalls can't drift. The strings must stay free of "web"/
            "stripe" substrings (§H.4 native anti-steering assertions). */}
        <h1 className={styles.h1}>{PAYWALL_HERO_TITLE}</h1>
        <p className={styles.heroSub}>{PAYWALL_HERO_SUB}</p>
      </header>

      <div className={styles.plans}>
        {plans.length === 0 ? (
          <div className={styles.card}>
            <p className={styles.heroSub}>Loading plans&hellip;</p>
          </div>
        ) : (
          plans.map((plan) => (
            <NativePlanCard
              key={plan.identifier}
              plan={plan}
              busy={busy}
              onSubscribe={() => subscribe(plan)}
            />
          ))
        )}
      </div>

      {/* MANDATORY Restore Purchases — directly below the plan cards, plainly
          visible (§C.3/§H.4). Its absence alone is a rejection. */}
      <div className={styles.restoreRow}>
        <Button
          variant="ghost"
          onClick={restore}
          loading={busy === "restore"}
          disabled={!!busy && busy !== "restore"}
        >
          Restore Purchases
        </Button>
      </div>

      {info && (
        <div className={styles.banner} role="status">
          <span className={styles.bannerTx}>{info}</span>
        </div>
      )}
      {error && (
        <div className={styles.error} role="alert">
          {error}
        </div>
      )}

      <FeaturesList features={sub.features} />

      {/* §6.5: the honest freemium boundary + mission framing (shared with the
          Stripe branch; Stripe-free by construction). */}
      <FreeForeverCard />
      <MissionNote />

      {/* Auto-renewal disclosure + functional Terms/Privacy links (§C.3/§H.4).
          Prices interpolated from StoreKit packages — never literals. */}
      <RenewalDisclosure monthly={monthly} annual={annual} />
    </div>
  );
}

function NativePlanCard({
  plan,
  busy,
  onSubscribe,
}: {
  plan: RcPlan;
  busy: string | null;
  onSubscribe: () => void;
}) {
  const isAnnual = plan.period === "year";
  const cls = [styles.card, styles.planCard, isAnnual && styles.planFeatured]
    .filter(Boolean)
    .join(" ");
  const label = isAnnual ? "Annual" : "Monthly";
  return (
    <div className={cls}>
      <div className={styles.planHead}>
        <span className={styles.planLabel}>{label}</span>
        {isAnnual && <Pill tone="green">Best value</Pill>}
      </div>

      <div className={styles.price}>
        {/* StoreKit-localized price string, rendered whole (it carries its own
            currency sign and grouping). */}
        <span className={styles.priceAmt}>{plan.priceString}</span>
        <span className={styles.pricePer}>/{plan.period}</span>
      </div>

      <Button
        variant="primary"
        full
        onClick={onSubscribe}
        loading={busy === plan.identifier}
        disabled={!!busy && busy !== plan.identifier}
        aria-label={`Subscribe to ${label}, ${plan.priceString} per ${plan.period}`}
      >
        Subscribe
      </Button>
    </div>
  );
}

function NativeActiveCard({
  sub,
  canManage,
  onManage,
}: {
  sub: SubscriptionVM;
  canManage: boolean;
  onManage: () => void;
}) {
  const tier = tierLabel(sub.currentTier);
  const renews = formatLongDate(sub.expiresAt);
  return (
    <div className={[styles.card, styles.activeCard].join(" ")}>
      <div className={styles.activeHead}>
        <span className={styles.checkChip} aria-hidden="true">
          <Icon name="check" size={16} stroke={3} />
        </span>
        <b className={styles.activeTitle}>You&rsquo;re on Pro{tier ? ` · ${tier}` : ""}</b>
      </div>
      <p className={styles.activeSub}>
        {renews
          ? `Renews ${renews}. Thanks for supporting your claim.`
          : "Thanks for supporting your claim."}
      </p>

      <FeaturesList features={sub.features} flush />

      {canManage && (
        <div className={styles.activeActions}>
          {/* Native StoreKit management sheet — NEVER the Stripe portal on iOS
              (§C.3/§C.5). Shown only for store-bought subs. */}
          <Button variant="ghost" icon="money" onClick={onManage}>
            Manage subscription
          </Button>
        </div>
      )}
    </div>
  );
}

function RenewalDisclosure({
  monthly,
  annual,
}: {
  monthly?: RcPlan;
  annual?: RcPlan;
}) {
  const m = monthly?.priceString;
  const a = annual?.priceString;
  // Build the price clause from whatever StoreKit returned (both expected).
  const priceClause =
    m && a
      ? `${m}/month or ${a}/year`
      : m
        ? `${m}/month`
        : a
          ? `${a}/year`
          : "the price shown above";
  return (
    <div className={styles.disclosure}>
      <p className={styles.disclosureBody}>
        After Duty Pro is an auto-renewing subscription: {priceClause}. Payment is charged to
        your Apple Account at confirmation of purchase. The subscription renews automatically
        unless cancelled at least 24 hours before the end of the current period. Manage or cancel
        anytime in your Apple Account settings.
      </p>
      <p className={styles.disclosureLinks}>
        <a href={EULA_URL} target="_blank" rel="noopener noreferrer">
          Terms of Use (EULA)
        </a>
        <span aria-hidden="true"> · </span>
        <a href={PRIVACY_URL} target="_blank" rel="noopener noreferrer">
          Privacy Policy
        </a>
      </p>
    </div>
  );
}
