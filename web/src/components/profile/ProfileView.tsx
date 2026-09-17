"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { Button } from "@/components/ui/Button";
import { Icon } from "@/components/ui/Icon";
import { Modal } from "@/components/ui/Modal";
import { useTheme } from "@/components/shell/ThemeProvider";
import { authDriver, type AccountDetails } from "@/lib/auth";
import { formatLongDate, formatPhoneUS, isSyntheticEmail, tierLabel } from "@/lib/format";
import { deleteAccount, updatePreferredName } from "@/lib/api/mutations";
import { rcLogOut, rcGetEntitlement, rcManageSubscriptions } from "@/lib/native/revenuecat";
import { tapLight, notifySuccess, notifyError } from "@/lib/native/haptics";
import { WebBillingManage } from "./WebBillingManage";
import { AddEmailSheet } from "./AddEmailSheet";
import { PasskeySection } from "./PasskeySection";
import { ServicePeriodsCard } from "./ServicePeriodsCard";
import { readBillingSource, deleteBillingWarning, type BillingSource } from "./billing-source";
import type { ProfileVM } from "@/lib/models/vm";
import type { ThemeName, TextScale } from "@/lib/theme/tokens";
import styles from "./ProfileView.module.css";

/** Real addresses only — a synthetic "@firebase.local" placeholder is "no email". */
function realEmail(email?: string | null): string | null {
  return email && !isSyntheticEmail(email) ? email : null;
}

const THEMES: { id: ThemeName; label: string }[] = [
  { id: "navy", label: "Navy" },
  { id: "warm", label: "Warm" },
  { id: "ai", label: "AI" },
];

const SIZES: { value: TextScale; label: string }[] = [
  { value: 0, label: "A−" },
  { value: 1, label: "A" },
  { value: 2, label: "A+" },
];

export function ProfileView({ profile }: { profile: ProfileVM }) {
  const { theme, scale, dark, setTheme, setScale, setDark } = useTheme();
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [expired, setExpired] = useState(false);
  const [account, setAccount] = useState<AccountDetails | null>(null);

  // "What should we call you?" — the editable preferred name. `name` may be ""
  // (uid-only phone sign-in): the header then says "Welcome" + the set-name
  // affordance, NEVER a uid or a synthetic email local-part.
  const [name, setName] = useState(profile.name);
  const [editingName, setEditingName] = useState(false);
  const [nameDraft, setNameDraft] = useState(profile.name);
  const [nameBusy, setNameBusy] = useState(false);
  const [nameError, setNameError] = useState<string | null>(null);

  // "Add an email" (phone-first accounts have none the UI may show). The
  // attach flow runs in AddEmailSheet; a success lands here so the row updates
  // without a reload.
  const [emailSheetOpen, setEmailSheetOpen] = useState(false);
  const [attachedEmail, setAttachedEmail] = useState<string | null>(null);

  useEffect(() => authDriver.watchAccount(setAccount), []);

  const subline = [profile.branch, profile.role === "admin" ? "Admin" : "Veteran"]
    .filter(Boolean)
    .join(" · ");

  const email = attachedEmail ?? realEmail(account?.email) ?? realEmail(profile.email);
  const phone = formatPhoneUS(account?.phoneNumber) ?? "—";

  function openNameEditor() {
    tapLight();
    setNameDraft(name);
    setNameError(null);
    setEditingName(true);
  }

  async function saveName(e: React.FormEvent) {
    e.preventDefault();
    const v = nameDraft.trim();
    if (!v) {
      setNameError("Enter a name — even just your first name.");
      return;
    }
    if (v.length > 60) {
      setNameError("Keep it under 60 characters.");
      return;
    }
    setNameBusy(true);
    setNameError(null);
    try {
      const res = await updatePreferredName(v);
      if (!res.ok) throw new Error(`patch ${res.status}`);
      notifySuccess();
      setName(v);
      setEditingName(false);
    } catch {
      notifyError();
      setNameError("Couldn't save your name. Please try again.");
    } finally {
      setNameBusy(false);
    }
  }

  async function onSignOut() {
    if (busy) return;
    setBusy(true);
    // signOut() awaits both Firebase sign-out and the BFF cookie clear, either
    // of which can reject (network). A failed cookie-clear must NOT trap the
    // user on a permanently-disabled button: always navigate to /login, which
    // re-runs the server auth gate and re-clears the cookie if it survived.
    try {
      await authDriver.signOut();
    } catch {
      /* fall through — navigation below re-asserts the signed-out state */
    } finally {
      window.location.assign("/login");
    }
  }

  async function onDelete() {
    tapLight();
    setBusy(true);
    setError(null);
    setExpired(false);
    const res = await deleteAccount();
    // P2-4: an expired session must offer the way back in, not a dead retry
    // loop — deleting an account is exactly when a veteran is least willing
    // to fight the UI.
    if (res.status === 401) {
      notifyError();
      setExpired(true);
      setBusy(false);
      return;
    }
    if (res.ok) {
      notifySuccess();
      // Native: plugin + JS-SDK sign-out (no cookie); web: JS sign-out + cookie
      // clear — both via the AuthDriver seam (§H.3). Also reset the RC subscriber
      // so the deleted account leaves no entitlement bound on-device (§C.1/§H.3);
      // no-op on web.
      await rcLogOut();
      await authDriver.signOut();
      window.location.assign("/login");
    } else {
      notifyError();
      setError("Couldn't delete your account. Please try again.");
      setBusy(false);
    }
  }

  function closeConfirm() {
    if (busy) return;
    setError(null);
    setExpired(false);
    setConfirmOpen(false);
  }

  const planTier = tierLabel(profile.planTier);
  const renews = formatLongDate(profile.planExpiresAt);
  // P1-21: where the subscription is billed (stripe | apple | google), read
  // defensively — null until the backend/VM expose it.
  const billingSource = readBillingSource(profile);
  const billingWarning = deleteBillingWarning(billingSource, profile.subState);

  return (
    <div className={styles.wrap}>
      {/* 1 · Profile header — displayName or "Welcome", never a uid. */}
      <div className={styles.card}>
        <div className={styles.header}>
          <div className={styles.avatar} aria-hidden="true">
            {(name.trim()[0] ?? profile.initial).toUpperCase()}
          </div>
          <div className={styles.headerTx}>
            {editingName ? (
              <form className={styles.nameEdit} onSubmit={saveName}>
                <input
                  className={styles.nameInput}
                  aria-label="Preferred name"
                  placeholder="First name"
                  maxLength={60}
                  value={nameDraft}
                  onChange={(e) => setNameDraft(e.target.value)}
                  disabled={nameBusy}
                  autoFocus
                />
                <Button type="submit" size="sm" loading={nameBusy}>
                  Save
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant="ghost"
                  disabled={nameBusy}
                  onClick={() => {
                    setEditingName(false);
                    setNameError(null);
                  }}
                >
                  Cancel
                </Button>
              </form>
            ) : name ? (
              <span className={styles.nameRow}>
                <b className={styles.name}>{name}</b>
                <button
                  type="button"
                  className={styles.nameEditBtn}
                  aria-label="Edit name"
                  onClick={openNameEditor}
                >
                  <Icon name="pen" size={15} stroke={2.1} />
                </button>
              </span>
            ) : (
              <>
                <b className={styles.name}>Welcome</b>
                <button type="button" className={styles.setNameBtn} onClick={openNameEditor}>
                  What should we call you?
                </button>
              </>
            )}
            {nameError && (
              <small className={styles.nameError} role="alert">
                {nameError}
              </small>
            )}
            {subline && <small className={styles.sub}>{subline}</small>}
          </div>
        </div>
      </div>

      {/* 2 · Upgrade to Pro (only a confirmed free user; never on unknown error) */}
      {profile.subState === "free" && (
        <Link href="/upgrade" className={[styles.card, styles.proCard].join(" ")}>
          <span className={styles.proChip} aria-hidden="true">
            <Icon name="sparkle" size={20} stroke={2.1} />
          </span>
          <span className={styles.proTx}>
            <b>Upgrade to Pro</b>
            <small>
              AI extraction, condition synthesis &amp; gap analysis &middot; $11.99/mo
            </small>
          </span>
          <Icon name="chevron" size={18} stroke={2.2} />
        </Link>
      )}

      {/* 2b · Account details */}
      <div>
        <div className={styles.secLabel}>Account details</div>
        <div className={styles.card}>
          {/* Plan row — Pro (with renewal + manage) / Free (upgrade) / unknown. */}
          <div className={styles.planRow}>
            <div className={styles.dataRow}>
              <span className={styles.dataKey}>Plan</span>
              {profile.subState === "pro" ? (
                <b className={styles.dataVal}>Pro{planTier ? ` · ${planTier}` : ""}</b>
              ) : profile.subState === "free" ? (
                <span className={styles.planFree}>
                  <b className={styles.dataVal}>Free</b>
                  <Link href="/upgrade" className={styles.planUpgrade}>
                    Upgrade
                  </Link>
                </span>
              ) : (
                <b className={styles.dataVal}>—</b>
              )}
            </div>
            {profile.subState === "pro" && (
              <BillingManage renews={renews} billingSource={billingSource} />
            )}
          </div>
          <div className={[styles.dataRow].join(" ")}>
            <span className={styles.dataKey}>Email</span>
            {email ? (
              <span className={styles.emailVal}>
                <b className={styles.dataVal}>{email}</b>
                {attachedEmail && (
                  <small className={styles.emailNote}>You can now sign in with it.</small>
                )}
              </span>
            ) : (
              <button
                type="button"
                className={styles.addEmailBtn}
                onClick={() => setEmailSheetOpen(true)}
              >
                Add an email
              </button>
            )}
          </div>
          <div className={styles.dataRow}>
            <span className={styles.dataKey}>Phone</span>
            <b className={styles.dataVal}>{phone}</b>
          </div>
          <div className={[styles.dataRow, styles.dataRowLast].join(" ")}>
            <span className={styles.dataKey}>Two-step verification</span>
            <b className={styles.dataVal}>{account?.mfaFactors.length ? "On (SMS)" : "Off"}</b>
          </div>
        </div>
      </div>

      {/* 2c · Passkeys (self-hides when WebAuthn is unsupported / on native). */}
      <PasskeySection />

      {/* 3 · Service summary — the periods list (Active / Guard / Reserve),
          populated from already-extracted document facts + any manual row. */}
      <ServicePeriodsCard periods={profile.servicePeriods ?? []} />

      {/* 4 · Appearance */}
      <div>
        <div className={styles.secLabel}>Appearance</div>
        <div className={styles.card}>
          {/* Dark mode */}
          <div className={[styles.ctrlRow, styles.ctrlRowLine].join(" ")}>
            <span className={styles.ctrlLabel}>Dark mode</span>
            <button
              type="button"
              className={styles.switch}
              data-on={dark ? "1" : "0"}
              role="switch"
              aria-checked={dark}
              aria-label="Dark mode"
              onClick={() => setDark(!dark)}
            >
              <span />
            </button>
          </div>

          {/* Theme */}
          <div className={[styles.ctrlRow, styles.ctrlRowLine].join(" ")}>
            <span className={styles.ctrlLabel}>Theme</span>
            <span className={styles.segGroup} role="group" aria-label="Theme">
              {THEMES.map((t) => (
                <button
                  key={t.id}
                  type="button"
                  className={styles.seg}
                  data-active={theme === t.id ? "1" : undefined}
                  aria-pressed={theme === t.id}
                  onClick={() => setTheme(t.id)}
                >
                  {t.label}
                </button>
              ))}
            </span>
          </div>

          {/* Text size */}
          <div className={styles.ctrlRow}>
            <span className={styles.ctrlLabel}>Text size</span>
            <span className={styles.segGroup} role="group" aria-label="Text size">
              {SIZES.map((s) => (
                <button
                  key={s.value}
                  type="button"
                  className={styles.seg}
                  data-active={scale === s.value ? "1" : undefined}
                  aria-pressed={scale === s.value}
                  aria-label={`Text size ${s.value === 0 ? "small" : s.value === 1 ? "default" : "large"}`}
                  onClick={() => setScale(s.value)}
                >
                  {s.label}
                </button>
              ))}
            </span>
          </div>
        </div>
      </div>

      {/* 5 · Account */}
      <div>
        <div className={styles.secLabel}>Account</div>
        <div className={styles.card}>
          <Link href="/timeline" className={[styles.linkRow, styles.linkRowLine].join(" ")}>
            <span className={styles.linkIcon} aria-hidden="true">
              <Icon name="clock" size={18} stroke={2.1} />
            </span>
            <span className={styles.linkLabel}>Claim timeline</span>
            <Icon name="chevron" size={16} stroke={2.2} />
          </Link>
          <Link href="/share" className={[styles.linkRow, styles.linkRowLine].join(" ")}>
            <span className={styles.linkIcon} aria-hidden="true">
              <Icon name="share" size={18} stroke={2.1} />
            </span>
            <span className={styles.linkLabel}>Share with a VSO</span>
            <Icon name="chevron" size={16} stroke={2.2} />
          </Link>
          <Link href="/learn" className={[styles.linkRow, styles.linkRowLine].join(" ")}>
            <span className={styles.linkIcon} aria-hidden="true">
              <Icon name="info" size={18} stroke={2.1} />
            </span>
            <span className={styles.linkLabel}>Learn how VA claims work</span>
            <Icon name="chevron" size={16} stroke={2.2} />
          </Link>
          <button type="button" className={styles.linkRow} onClick={onSignOut} disabled={busy}>
            <span className={styles.linkIcon} aria-hidden="true">
              <Icon name="back" size={18} stroke={2.1} />
            </span>
            <span className={styles.linkLabel}>Sign out</span>
          </button>
        </div>
      </div>

      {/* 6 · Danger zone */}
      <div className={styles.danger}>
        <button
          type="button"
          className={styles.deleteBtn}
          onClick={() => setConfirmOpen(true)}
        >
          Delete account
        </button>
      </div>

      <AddEmailSheet
        open={emailSheetOpen}
        onClose={() => setEmailSheetOpen(false)}
        onAttached={(attached) => {
          notifySuccess();
          setAttachedEmail(attached);
          setEmailSheetOpen(false);
        }}
      />

      <Modal open={confirmOpen} onClose={closeConfirm} title="Delete your account?" size="sm">
        <p className={styles.modalBody}>
          This permanently deletes your account and all your data &mdash; conditions, evidence,
          and shares. This cannot be undone.
        </p>
        {/* P1-21 / App Review 5.1.1(v): a store-billed subscription keeps
            charging after the account is gone — say so before they confirm. */}
        {billingWarning && <p className={styles.modalBillingNote}>{billingWarning}</p>}
        {expired && (
          <div className={styles.modalError} role="alert">
            Your session has expired.{" "}
            <Link
              className={styles.errorLink}
              href={`/login?next=${encodeURIComponent("/profile")}`}
            >
              Sign in again
            </Link>{" "}
            to continue.
          </div>
        )}
        {error && <div className={styles.modalError}>{error}</div>}
        <div className={styles.modalActions}>
          <Button variant="ghost" full onClick={closeConfirm} disabled={busy}>
            Cancel
          </Button>
          <Button
            variant="primary"
            full
            className={styles.confirmDanger}
            onClick={onDelete}
            loading={busy}
          >
            Delete everything
          </Button>
        </div>
      </Modal>
    </div>
  );
}

/**
 * Billing-manage dispatcher (§C.5). Selects native vs web by the INLINED
 * `NEXT_PUBLIC_NATIVE` literal (statement-form `if`, not a ternary) so webpack
 * dead-code-eliminates the `WebBillingManage` import — and its Stripe
 * "billing portal" copy — from the native export (§H.4 anti-steering). On web the
 * literal is unset, so the Stripe branch ships and renders.
 */
function BillingManage({
  renews,
  billingSource,
}: {
  renews: string | null;
  billingSource: BillingSource | null;
}) {
  if (process.env.NEXT_PUBLIC_NATIVE === "1") {
    return <NativeBillingManage renews={renews} />;
  }
  return <WebBillingManage renews={renews} billingSource={billingSource} />;
}

/**
 * Native profile billing-row manage block (§C.5/§H.4 matrix). There is no Stripe
 * portal on iOS. The store of record is read from RevenueCat's local entitlement
 * cache (the same on-device oracle the paywall uses) — no backend `source` field
 * is needed:
 *   - store-bought (RC entitlement active) → "Manage subscription" → native
 *     StoreKit management sheet (NEVER the Stripe portal).
 *   - web/Stripe subscriber (entitlement honored by the backend, but no RC
 *     entitlement on this device) → neutral static text, NO tappable link and NO
 *     mention of "the web" — even naming the web portal is steering-adjacent, so
 *     we take the zero-risk posture (§C.5 addendum).
 * While the oracle resolves, show nothing extra (the Pro label already rendered).
 */
function NativeBillingManage({ renews }: { renews: string | null }) {
  const [storeManaged, setStoreManaged] = useState<boolean | null>(null);

  useEffect(() => {
    let live = true;
    rcGetEntitlement().then((e) => live && setStoreManaged(Boolean(e?.active)));
    return () => {
      live = false;
    };
  }, []);

  if (storeManaged === null) {
    return renews ? (
      <div className={styles.planManage}>
        <small className={styles.planRenews}>Renews {renews}</small>
      </div>
    ) : null;
  }

  if (storeManaged) {
    return (
      <div className={styles.planManage}>
        {renews && <small className={styles.planRenews}>Renews {renews}</small>}
        <button
          type="button"
          className={styles.manageBtn}
          onClick={() => {
            tapLight();
            void rcManageSubscriptions();
          }}
        >
          Manage subscription
        </button>
      </div>
    );
  }

  // Cross-platform (web/Stripe) subscriber — entitlement honored, no purchase UI,
  // no manage link, neutral copy.
  return (
    <div className={styles.planManage}>
      {renews && <small className={styles.planRenews}>Renews {renews}</small>}
      <small className={styles.planRenews}>
        Your subscription is managed where you purchased it.
      </small>
    </div>
  );
}
