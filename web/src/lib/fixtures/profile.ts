import type { ProfileVM } from "@/lib/models/vm";

export const profileFixture: ProfileVM = {
  name: "D. Griff",
  email: "d.griff@example.com",
  initial: "G",
  branch: "U.S. Army",
  service: "2004 – 2010",
  mos: "11B",
  // One six-year Army enlistment (D. Griff, the synthetic store-screenshot
  // veteran): SGT, 11B, one OIF tour. A reconciled conclusion — two government
  // records for the enlistment collapsed into one, carrying the drill-down
  // receipts + reasoning — plus a manual row, so the component labels, the
  // total-years line, and both source chips are all exercised.
  servicePeriods: [
    {
      branch: "Army",
      component: "active",
      startDate: "2004-08-16",
      endDate: "2010-08-15",
      mos: "11B",
      rank: "SGT",
      source: "documents",
      sources: [
        {
          evidenceId: 101,
          docType: "DD-214",
          authorityRank: 100,
          rawBranch: "Army",
          rawStart: "2004-08-16",
          rawEnd: "2010-08-15",
          rawMos: "11B",
          rawRank: "SGT",
        },
        {
          evidenceId: 102,
          docType: "Personnel record",
          authorityRank: 40,
          rawBranch: "U.S. Army",
          rawStart: "2004-08-01",
          rawEnd: "2010-08-15",
          rawMos: "11 Bravo",
          rawRank: "Sergeant",
        },
      ],
      reasoning:
        "Merged 2 records for this enlistment. Dates, branch, MOS, and rank taken from the DD-214 (the authoritative discharge document); the personnel record agreed on the end date and confirmed the branch.",
      totalYears: 6,
      clusterKey: "Army|active|2004",
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
  ],
  isPro: false,
  subState: "free",
  planTier: null,
  planExpiresAt: null,
  billingSource: null,
  role: "veteran",
};

/** Active Pro subscriber — exercises the Plan row + "Manage subscription". */
export const profileProFixture: ProfileVM = {
  ...profileFixture,
  isPro: true,
  subState: "pro",
  planTier: "annual",
  planExpiresAt: "2027-01-15T00:00:00Z",
  billingSource: "portal",
};

/**
 * Uid-only phone sign-in with nothing extracted yet: no usable name (the view
 * must say "Welcome", never a uid), no real email ("Add an email" affordance),
 * and the service empty state.
 */
export const profileNamelessFixture: ProfileVM = {
  ...profileFixture,
  name: "",
  email: null,
  initial: "U",
  branch: null,
  service: null,
  mos: null,
  servicePeriods: [],
};
