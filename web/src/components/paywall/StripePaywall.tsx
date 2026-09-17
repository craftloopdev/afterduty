"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/Button";
import { Icon } from "@/components/ui/Icon";
import { Pill } from "@/components/ui/Pill";
import { formatLongDate, tierLabel } from "@/lib/format";
import { requestSubscriptionUrl } from "@/lib/web-only/stripe-actions";
import type { CheckoutOutcome } from "@/lib/checkout-return";
import type { PlanVM, SubscriptionVM } from "@/lib/models/vm";
import {
  FeaturesList,
  FreeForeverCard,
  MissionNote,
  PAYWALL_HERO_SUB,
  PAYWALL_HERO_TITLE,
} from "./PaywallParts";
import styles from "./PaywallView.module.css";

// The WEB Stripe paywall branch (capacitor-ios-spec §C.3). Extracted into its own
// module — mirroring `WebBillingManage` — so the ONLY `requestSubscriptionUrl`
// import (and every Stripe string: "/api/subscription", "billing portal") lives
// here, never in `PaywallView.tsx`. `PaywallView` selects this on the web build
// and statically drops it on native (the `NEXT_PUBLIC_NATIVE` literal makes the
// dynamic `require` in this module's `stripe-actions` facade dead), so the §H.4
// anti-steering audit's `web/out/` grep stays at zero matches even across
// refactors. Behavior is byte-identical to the prior in-file `StripePaywall`.

type Action = "checkout" | "portal";

/** Banner variant: success is only asserted once the refetch confirms Pro. */
type BannerVariant = "success" | "finalizing" | "cancelled";

export function StripePaywall({
  sub,
  checkout,
}: {
  sub: SubscriptionVM;
  checkout: CheckoutOutcome;
}) {
  const router = useRouter();
  // `busy` holds the plan tier (for checkout) or "portal" (for the portal),
  // so each button shows its own loading state. While ANY action is in flight,
  // all sibling buttons disable to prevent a double-checkout race.
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [dismissed, setDismissed] = useState(false);

  // After a successful Stripe return the server render may predate the webhook,
  // so refetch once to pick up the now-active subscription.
  useEffect(() => {
    if (checkout === "success") router.refresh();
  }, [checkout, router]);

  // Gate the banner on the REFETCHED truth, not just the URL. A success URL
  // (or a spoofed `?session_id=…`) only earns the "welcome to Pro" copy once
  // `sub.active` confirms it; otherwise show a neutral "finalizing…" state so
  // we never assert an active subscription the backend hasn't granted. The
  // cancelled banner can stay URL-driven (it asserts nothing about access).
  const bannerVariant: BannerVariant | null = dismissed
    ? null
    : checkout === "success"
      ? sub.active
        ? "success"
        : "finalizing"
      : checkout === "cancelled"
        ? "cancelled"
        : null;

  async function go(action: Action, tier?: string) {
    if (busy) return; // guard: ignore clicks while another action is in flight
    setBusy(tier ?? action);
    setError(null);
    const result = await requestSubscriptionUrl(action, tier);
    if (result.ok) {
      window.location.assign(result.url);
      return; // leave `busy` set — we're navigating away
    }
    setError(
      result.reason === "forbidden"
        ? "That action isn't available on your account."
        : action === "portal"
          ? "Couldn't open the billing portal. Please try again."
          : "Couldn't start checkout. Please try again.",
    );
    setBusy(null);
  }

  // Honest unknown: the status fetch failed. NEVER show live Subscribe buttons
  // here — a paying user could be double-charged.
  if (sub.state === "error") {
    return (
      <div className={styles.wrap}>
        <ErrorState onRetry={() => router.refresh()} />
      </div>
    );
  }

  return (
    <div className={styles.wrap}>
      {bannerVariant && (
        <CheckoutBanner variant={bannerVariant} onDismiss={() => setDismissed(true)} />
      )}

      {sub.active ? (
        <ActiveCard sub={sub} busy={busy} onManage={() => go("portal")} />
      ) : (
        <>
          <header className={styles.hero}>
            <span className={styles.heroChip} aria-hidden="true">
              <Icon name="sparkle" size={22} stroke={2.1} />
            </span>
            <h1 className={styles.h1}>{PAYWALL_HERO_TITLE}</h1>
            <p className={styles.heroSub}>{PAYWALL_HERO_SUB}</p>
          </header>

          <div className={styles.plans}>
            {sub.plans.map((plan) => (
              <PlanCard
                key={plan.tier}
                plan={plan}
                busy={busy}
                onSubscribe={() => go("checkout", plan.tier)}
              />
            ))}
          </div>

          <FeaturesList features={sub.features} />

          {/* §6.5: the honest freemium boundary + mission framing. */}
          <FreeForeverCard />
          <MissionNote />
        </>
      )}

      {error && (
        <div className={styles.error} role="alert">
          {error}
        </div>
      )}
    </div>
  );
}

const BANNER_COPY: Record<BannerVariant, string> = {
  // Only shown once the refetch confirms `sub.active` — safe to assert active.
  success: "You're all set — welcome to Pro. Your subscription is active.",
  // Checkout returned success but the webhook hasn't granted access yet (or a
  // spoofed session_id). Never assert "active" here.
  finalizing:
    "Thanks! We're finalizing your subscription — this can take a moment. " +
    "It'll unlock automatically; refresh if it doesn't appear shortly.",
  cancelled: "No charge was made. You can pick a plan whenever you're ready.",
};

function CheckoutBanner({
  variant,
  onDismiss,
}: {
  variant: BannerVariant;
  onDismiss: () => void;
}) {
  const success = variant === "success";
  return (
    <div
      className={[styles.banner, success ? styles.bannerOk : styles.bannerNeutral].join(" ")}
      role="status"
    >
      <span className={styles.bannerIc} aria-hidden="true">
        <Icon name={success ? "check" : "info"} size={16} stroke={2.4} />
      </span>
      <span className={styles.bannerTx}>{BANNER_COPY[variant]}</span>
      <button
        type="button"
        className={styles.bannerClose}
        onClick={onDismiss}
        aria-label="Dismiss"
      >
        <Icon name="close" size={15} stroke={2.4} />
      </button>
    </div>
  );
}

function ErrorState({ onRetry }: { onRetry: () => void }) {
  return (
    <div className={[styles.card, styles.errorCard].join(" ")} role="alert">
      <span className={styles.errorChip} aria-hidden="true">
        <Icon name="info" size={22} stroke={2.2} />
      </span>
      <b className={styles.errorTitle}>We couldn&rsquo;t load your subscription</b>
      <p className={styles.errorBody}>
        Your plan status didn&rsquo;t load just now, so we&rsquo;re not showing plans to avoid a
        mistaken charge. If you&rsquo;re already on Pro, your access is unaffected.
      </p>
      <Button variant="primary" onClick={onRetry}>
        Try again
      </Button>
    </div>
  );
}

function ActiveCard({
  sub,
  busy,
  onManage,
}: {
  sub: SubscriptionVM;
  busy: string | null;
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

      <div className={styles.activeActions}>
        <Button
          variant="ghost"
          icon="money"
          onClick={onManage}
          loading={busy === "portal"}
          disabled={!!busy && busy !== "portal"}
        >
          Manage subscription
        </Button>
      </div>
    </div>
  );
}

function PlanCard({
  plan,
  busy,
  onSubscribe,
}: {
  plan: PlanVM;
  busy: string | null;
  onSubscribe: () => void;
}) {
  const isAnnual = plan.period === "year" || plan.label === "Annual";
  const cls = [styles.card, styles.planCard, isAnnual && styles.planFeatured]
    .filter(Boolean)
    .join(" ");

  return (
    <div className={cls}>
      <div className={styles.planHead}>
        <span className={styles.planLabel}>{plan.label}</span>
        {isAnnual && <Pill tone="green">Best value</Pill>}
      </div>

      <div className={styles.price}>
        <span className={styles.priceAmt}>${plan.price.toFixed(2)}</span>
        <span className={styles.pricePer}>/{plan.period}</span>
      </div>

      <Button
        variant="primary"
        full
        onClick={onSubscribe}
        loading={busy === plan.tier}
        disabled={!!busy && busy !== plan.tier}
        aria-label={`Subscribe to ${plan.label}, $${plan.price.toFixed(2)} per ${plan.period}`}
      >
        Subscribe
      </Button>
    </div>
  );
}
