import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ProfileView } from "./ProfileView";
import { EmailCodeError } from "@/lib/auth/email-code-client";
import type { AccountDetails } from "@/lib/auth/driver";
import type { ProfileVM, ServicePeriodVM } from "@/lib/models/vm";

// ProfileView signs out through the AuthDriver seam (`@/lib/auth`): on web that
// awaits Firebase sign-out + a BFF cookie-clear, either of which can reject.
// watchAccount replays `accountValue` synchronously so tests can inject the
// Firebase-side email/phone; the email-code fns drive the Add-an-email sheet.
const signOut = vi.fn();
const requestEmailCode = vi.fn();
const attachEmailCode = vi.fn();
let accountValue: AccountDetails | null = null;
vi.mock("@/lib/auth", () => ({
  authDriver: {
    signOut: () => signOut(),
    watchAccount: (cb: (d: AccountDetails | null) => void) => {
      cb(accountValue);
      return () => {};
    },
    requestEmailCode: (...a: unknown[]) => requestEmailCode(...a),
    attachEmailCode: (...a: unknown[]) => attachEmailCode(...a),
  },
}));
// Avoid the full ThemeProvider/cookie machinery — the appearance controls just
// need a stable context value here.
vi.mock("@/components/shell/ThemeProvider", () => ({
  useTheme: () => ({
    theme: "navy",
    scale: 1,
    dark: false,
    setTheme: vi.fn(),
    setScale: vi.fn(),
    setDark: vi.fn(),
  }),
}));
vi.mock("@/lib/subscription-actions", () => ({
  requestSubscriptionUrl: vi.fn(),
}));
// The delete-account and preferred-name writes go through the mutations
// facade; only tests that exercise them set an implementation.
const deleteAccount = vi.fn();
const updatePreferredName = vi.fn();
vi.mock("@/lib/api/mutations", async (importOriginal) => ({
  ...(await importOriginal<Record<string, unknown>>()),
  deleteAccount: (...a: unknown[]) => deleteAccount(...a),
  updatePreferredName: (...a: unknown[]) => updatePreferredName(...a),
}));

const PROFILE: ProfileVM = {
  name: "Test Vet",
  email: "vet@example.com",
  initial: "T",
  branch: "Army",
  service: null,
  mos: null,
  servicePeriods: [],
  isPro: false,
  subState: "free",
  planTier: null,
  planExpiresAt: null,
  billingSource: null,
  role: "veteran",
};

let assign: ReturnType<typeof vi.fn>;
beforeEach(() => {
  signOut.mockReset();
  deleteAccount.mockReset();
  updatePreferredName.mockReset();
  requestEmailCode.mockReset();
  attachEmailCode.mockReset();
  accountValue = null;
  assign = vi.fn();
  // jsdom's window.location.assign is a non-configurable no-op; replace it.
  Object.defineProperty(window, "location", {
    configurable: true,
    value: { assign, href: "http://localhost/profile" },
  });
});

const PRO: ProfileVM = {
  ...PROFILE,
  isPro: true,
  subState: "pro",
  planTier: "monthly",
};

/** ProfileVM with the (soon-to-land) billing-source field the UI reads defensively. */
const withSource = (base: ProfileVM, billingSource: string): ProfileVM =>
  ({ ...base, billingSource }) as ProfileVM;

async function openDeleteModal() {
  await userEvent.click(screen.getByRole("button", { name: /delete account/i }));
  return screen.getByRole("dialog", { name: /delete your account/i });
}

describe("ProfileView — delete-account modal branches on billing source (P1-21)", () => {
  const APPLE_WARNING = /does not cancel your apple subscription/i;
  const GENERIC_WARNING = /app store or google play/i;

  it("apple-billed Pro: warns that deletion does NOT cancel the Apple subscription", async () => {
    render(<ProfileView profile={withSource(PRO, "apple")} />);
    await openDeleteModal();
    expect(screen.getByText(APPLE_WARNING)).toBeInTheDocument();
    expect(screen.queryByText(GENERIC_WARNING)).not.toBeInTheDocument();
  });

  it("stripe-billed Pro: current behavior — no store warning", async () => {
    render(<ProfileView profile={withSource(PRO, "portal")} />);
    await openDeleteModal();
    expect(screen.queryByText(APPLE_WARNING)).not.toBeInTheDocument();
    expect(screen.queryByText(GENERIC_WARNING)).not.toBeInTheDocument();
  });

  it("Pro without a billing source (backend field absent): generic line covering both stores", async () => {
    render(<ProfileView profile={PRO} />);
    await openDeleteModal();
    expect(screen.getByText(GENERIC_WARNING)).toBeInTheDocument();
  });

  it("unknown subscription state is NEVER treated as free — generic warning still shows", async () => {
    render(<ProfileView profile={{ ...PROFILE, subState: "error" }} />);
    await openDeleteModal();
    expect(screen.getByText(GENERIC_WARNING)).toBeInTheDocument();
  });

  it("confirmed-free user: no subscription warning noise", async () => {
    render(<ProfileView profile={PROFILE} />);
    await openDeleteModal();
    expect(screen.queryByText(APPLE_WARNING)).not.toBeInTheDocument();
    expect(screen.queryByText(GENERIC_WARNING)).not.toBeInTheDocument();
  });
});

describe("ProfileView — web Manage subscription must not dead-end for store-billed Pros (P1-21)", () => {
  it("apple-billed Pro on web: App Store pointer instead of the Stripe portal button", () => {
    render(<ProfileView profile={withSource(PRO, "apple")} />);
    expect(screen.getByText(/manage your subscription in the app store/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^manage subscription$/i })).not.toBeInTheDocument();
  });

  it("stripe-billed Pro on web: the Stripe portal button stays", () => {
    render(<ProfileView profile={withSource(PRO, "portal")} />);
    expect(screen.getByRole("button", { name: /^manage subscription$/i })).toBeInTheDocument();
  });

  it("billing source absent: today's Stripe portal behavior is unchanged", () => {
    render(<ProfileView profile={PRO} />);
    expect(screen.getByRole("button", { name: /^manage subscription$/i })).toBeInTheDocument();
  });
});

describe("ProfileView — expired session on delete offers the way back in (P2-4)", () => {
  it("a 401 shows 'Sign in again' linking /login?next=/profile instead of a dead retry", async () => {
    deleteAccount.mockResolvedValue(new Response(null, { status: 401 }));
    render(<ProfileView profile={PROFILE} />);
    await openDeleteModal();
    await userEvent.click(screen.getByRole("button", { name: /delete everything/i }));

    const link = await screen.findByRole("link", { name: /sign in again/i });
    expect(link).toHaveAttribute("href", "/login?next=%2Fprofile");
    // No sign-out/navigation happened — the account was NOT deleted.
    expect(assign).not.toHaveBeenCalled();
    // The generic failure copy (a lie here — retrying can't succeed) stays hidden.
    expect(screen.queryByText(/couldn't delete your account/i)).not.toBeInTheDocument();
  });

  it("other failures keep the honest retryable error", async () => {
    deleteAccount.mockResolvedValue(new Response(null, { status: 502 }));
    render(<ProfileView profile={PROFILE} />);
    await openDeleteModal();
    await userEvent.click(screen.getByRole("button", { name: /delete everything/i }));
    expect(await screen.findByText(/couldn't delete your account/i)).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /sign in again/i })).not.toBeInTheDocument();
  });
});

const UID = "dB34I0uAgdXTBTL7bDq5kzrAb0M2";

describe("ProfileView — header never shows a uid; nameless gets 'Welcome' + the set-name affordance", () => {
  it("renders 'Welcome' and 'What should we call you?' when the VM has no usable name", () => {
    render(<ProfileView profile={{ ...PROFILE, name: "", email: null, initial: "U" }} />);
    expect(screen.getByText("Welcome")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /what should we call you/i }),
    ).toBeInTheDocument();
    expect(screen.queryByText(UID)).not.toBeInTheDocument();
    expect(screen.queryByText(new RegExp(UID))).not.toBeInTheDocument();
  });

  it("shows the display name with a pencil when one exists", () => {
    render(<ProfileView profile={PROFILE} />);
    expect(screen.getByText("Test Vet")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /edit name/i })).toBeInTheDocument();
  });
});

describe("ProfileView — editable preferred name (PATCH /api/auth/me)", () => {
  it("saves through the mutations facade and updates the header in place", async () => {
    updatePreferredName.mockResolvedValue(
      new Response(JSON.stringify({ ok: true, preferredName: "Sean" }), { status: 200 }),
    );
    render(<ProfileView profile={{ ...PROFILE, name: "", email: null, initial: "U" }} />);
    await userEvent.click(screen.getByRole("button", { name: /what should we call you/i }));
    await userEvent.type(screen.getByRole("textbox", { name: /preferred name/i }), "Sean");
    await userEvent.click(screen.getByRole("button", { name: /^save$/i }));

    expect(await screen.findByText("Sean")).toBeInTheDocument();
    expect(updatePreferredName).toHaveBeenCalledWith("Sean");
    expect(screen.queryByText("Welcome")).not.toBeInTheDocument();
  });

  it("keeps the editor open with honest copy when the save fails", async () => {
    updatePreferredName.mockResolvedValue(new Response(null, { status: 502 }));
    render(<ProfileView profile={PROFILE} />);
    await userEvent.click(screen.getByRole("button", { name: /edit name/i }));
    const input = screen.getByRole("textbox", { name: /preferred name/i });
    await userEvent.clear(input);
    await userEvent.type(input, "Sean");
    await userEvent.click(screen.getByRole("button", { name: /^save$/i }));

    expect(await screen.findByText(/couldn't save your name/i)).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: /preferred name/i })).toBeInTheDocument();
  });

  it("rejects an empty name client-side without calling the API", async () => {
    render(<ProfileView profile={{ ...PROFILE, name: "", email: null }} />);
    await userEvent.click(screen.getByRole("button", { name: /what should we call you/i }));
    await userEvent.click(screen.getByRole("button", { name: /^save$/i }));
    expect(await screen.findByText(/enter a name/i)).toBeInTheDocument();
    expect(updatePreferredName).not.toHaveBeenCalled();
  });
});

describe("ProfileView — email row: synthetic emails never render; Add-an-email attaches via OTP", () => {
  const NAMELESS = { ...PROFILE, name: "", email: null, initial: "U" };

  it("offers 'Add an email' when the VM email is null (synthetic filtered upstream)", () => {
    render(<ProfileView profile={NAMELESS} />);
    expect(screen.getByRole("button", { name: /add an email/i })).toBeInTheDocument();
  });

  it("filters a synthetic email that leaks through an older VM/driver defensively", () => {
    accountValue = { email: `${UID}@firebase.local`, phoneNumber: null, mfaFactors: [] };
    render(
      <ProfileView profile={{ ...NAMELESS, email: `${UID}@firebase.local` } as ProfileVM} />,
    );
    expect(screen.queryByText(new RegExp(UID))).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /add an email/i })).toBeInTheDocument();
  });

  it("happy path: request code (purpose attach) → verify → row shows the email + sign-in note", async () => {
    requestEmailCode.mockResolvedValue(undefined);
    attachEmailCode.mockResolvedValue(undefined);
    render(<ProfileView profile={NAMELESS} />);

    await userEvent.click(screen.getByRole("button", { name: /add an email/i }));
    const dialog = screen.getByRole("dialog", { name: /add an email/i });
    expect(dialog).toBeInTheDocument();

    await userEvent.type(screen.getByRole("textbox", { name: /email address/i }), "Sean@Example.com");
    await userEvent.click(screen.getByRole("button", { name: /send me a code/i }));
    expect(requestEmailCode).toHaveBeenCalledWith("sean@example.com", "attach");

    await userEvent.type(
      await screen.findByRole("textbox", { name: /verification code/i }),
      "123456",
    );
    await userEvent.click(screen.getByRole("button", { name: /verify email/i }));
    expect(attachEmailCode).toHaveBeenCalledWith("sean@example.com", "123456");

    // Sheet closes; the row now shows the attached address + the sign-in note.
    await waitFor(() =>
      expect(screen.queryByRole("dialog", { name: /add an email/i })).not.toBeInTheDocument(),
    );
    expect(screen.getByText("sean@example.com")).toBeInTheDocument();
    expect(screen.getByText(/you can now sign in with it/i)).toBeInTheDocument();
  });

  it("surfaces the backend's user-facing {detail} copy on failure (409 owned elsewhere)", async () => {
    requestEmailCode.mockResolvedValue(undefined);
    attachEmailCode.mockRejectedValue(
      new EmailCodeError(409, "That email is already in use by another account."),
    );
    render(<ProfileView profile={NAMELESS} />);

    await userEvent.click(screen.getByRole("button", { name: /add an email/i }));
    await userEvent.type(screen.getByRole("textbox", { name: /email address/i }), "vet@x.com");
    await userEvent.click(screen.getByRole("button", { name: /send me a code/i }));
    await userEvent.type(
      await screen.findByRole("textbox", { name: /verification code/i }),
      "654321",
    );
    await userEvent.click(screen.getByRole("button", { name: /verify email/i }));

    expect(
      await screen.findByText(/already in use by another account/i),
    ).toBeInTheDocument();
    // Sheet stays open for a retry.
    expect(screen.getByRole("dialog", { name: /add an email/i })).toBeInTheDocument();
  });

  it("falls back to the login-page tone when the failure carries no detail", async () => {
    requestEmailCode.mockRejectedValue(new Error("network down"));
    render(<ProfileView profile={NAMELESS} />);
    await userEvent.click(screen.getByRole("button", { name: /add an email/i }));
    await userEvent.type(screen.getByRole("textbox", { name: /email address/i }), "vet@x.com");
    await userEvent.click(screen.getByRole("button", { name: /send me a code/i }));
    expect(await screen.findByText(/couldn't send a code\. please try again\./i)).toBeInTheDocument();
  });
});

describe("ProfileView — phone row formats E.164 US numbers", () => {
  it("renders (202) 555-0147 for +12025550147", () => {
    accountValue = { email: null, phoneNumber: "+12025550147", mfaFactors: [] };
    render(<ProfileView profile={PROFILE} />);
    expect(screen.getByText("(202) 555-0147")).toBeInTheDocument();
  });

  it("renders non-US numbers as-is", () => {
    accountValue = { email: null, phoneNumber: "+447911123456", mfaFactors: [] };
    render(<ProfileView profile={PROFILE} />);
    expect(screen.getByText("+447911123456")).toBeInTheDocument();
  });
});

describe("ProfileView — service periods (pinned /auth/profile contract)", () => {
  const PERIODS: ServicePeriodVM[] = [
    {
      branch: "Army National Guard",
      component: "guard",
      startDate: "2008-03-01",
      endDate: null,
      mos: null,
      rank: "SSG",
      source: "documents",
      sources: null,
      reasoning: null,
      totalYears: null,
      clusterKey: null,
    },
    {
      branch: "Army",
      component: "active",
      startDate: "2003-06-10",
      endDate: "2007-06-09",
      mos: "11B",
      rank: "SGT",
      source: "documents",
      sources: null,
      reasoning: null,
      totalYears: null,
      clusterKey: null,
    },
    {
      branch: "Army",
      component: null,
      startDate: null,
      endDate: null,
      mos: "11B",
      rank: null,
      source: "manual",
      sources: null,
      reasoning: null,
      totalYears: null,
      clusterKey: null,
    },
  ];

  it("renders every period with component label, dates (Present for open), MOS/rank, and source chips", () => {
    render(<ProfileView profile={{ ...PROFILE, servicePeriods: PERIODS }} />);
    expect(screen.getByText("Army National Guard · National Guard")).toBeInTheDocument();
    expect(screen.getByText("Army · Active Duty")).toBeInTheDocument();
    expect(screen.getByText(/2008 – Present/)).toBeInTheDocument();
    expect(screen.getByText(/2003 – 2007 · MOS 11B · SGT/)).toBeInTheDocument();
    expect(screen.getAllByText("From your documents")).toHaveLength(2);
    expect(screen.getByText("Added by you")).toBeInTheDocument();
  });

  it("shows the total-years line when dates allow", () => {
    render(<ProfileView profile={{ ...PROFILE, servicePeriods: PERIODS }} />);
    expect(screen.getByText(/about \d+ years total/i)).toBeInTheDocument();
  });

  it("omits the total-years line when no period carries dates", () => {
    render(
      <ProfileView
        profile={{ ...PROFILE, servicePeriods: [PERIODS[2]] }}
      />,
    );
    expect(screen.queryByText(/years total/i)).not.toBeInTheDocument();
  });

  it("empty state points at the DD-214 upload", () => {
    render(<ProfileView profile={{ ...PROFILE, servicePeriods: [] }} />);
    // `.` matches the typographic apostrophe the component renders (&rsquo;).
    expect(
      screen.getByText(/upload your dd-214 and we.ll fill this in automatically/i),
    ).toBeInTheDocument();
  });

  it("links to the dedicated Service History screen when periods exist", () => {
    render(<ProfileView profile={{ ...PROFILE, servicePeriods: PERIODS }} />);
    const link = screen.getByRole("link", { name: /view full service history/i });
    expect(link).toHaveAttribute("href", "/service-history");
  });

  it("hides the Service History link when there are no periods (empty card)", () => {
    render(<ProfileView profile={{ ...PROFILE, servicePeriods: [] }} />);
    expect(
      screen.queryByRole("link", { name: /view full service history/i }),
    ).not.toBeInTheDocument();
  });
});

describe("ProfileView — sign-out never deadlocks the button", () => {
  it("navigates to /login on a normal sign-out", async () => {
    signOut.mockResolvedValue(undefined);
    render(<ProfileView profile={PROFILE} />);
    await userEvent.click(screen.getByRole("button", { name: /sign out/i }));
    await waitFor(() => expect(assign).toHaveBeenCalledWith("/login"));
  });

  it("still navigates to /login when signOut() rejects (failed cookie-clear must not trap the user)", async () => {
    // This is the regression: previously a rejected signOut() left busy=true
    // forever and never navigated, stranding the veteran on a dead button.
    signOut.mockRejectedValue(new Error("Could not establish session (503)"));
    render(<ProfileView profile={PROFILE} />);
    await userEvent.click(screen.getByRole("button", { name: /sign out/i }));
    await waitFor(() => expect(assign).toHaveBeenCalledWith("/login"));
  });
});
