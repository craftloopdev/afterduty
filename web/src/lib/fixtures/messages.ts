import type { MessageVM } from "@/lib/models/vm";

export const messagesFixture: MessageVM[] = [
  { id: "1", role: "user", content: "What's the strongest part of my claim?" },
  {
    id: "2",
    role: "assistant",
    content:
      "Your PTSD claim is the strongest — all three legs (diagnosis, in-service stressor, and nexus) are well documented, supporting a 70% rating. Asthma is also solid as a PACT Act presumptive. The biggest opportunity is a nexus letter for your sleep apnea.",
  },
];

/** Rich variant: markdown formatting + cite: citations (CFR + doc). */
export const messagesMarkdownFixture: MessageVM[] = [
  { id: "1", role: "user", content: "How is my knee rated?" },
  {
    id: "2",
    role: "assistant",
    content: [
      "Your knee is rated under the schedule for **limitation of flexion**:",
      "",
      "- A 10% rating applies when flexion is limited to 45°.",
      "- A 20% rating applies when flexion is limited to 30°.",
      "",
      "See [38 CFR § 4.71a (as of 2026-06-09)](cite:cfr/4.71a) for the full criteria.",
      "",
      "Your [March 2019 C&P exam, 2019-03-02](cite:doc/42) measured flexion at 40°, which supports the **10%** tier today.",
    ].join("\n"),
  },
];

/** Failed-send variant: an optimistic user bubble that did not get a reply. */
export const messagesFailedFixture: MessageVM[] = [
  { id: "1", role: "user", content: "What's the strongest part of my claim?" },
  {
    id: "2",
    role: "assistant",
    content:
      "Your PTSD claim is the strongest — all three legs are well documented, supporting a 70% rating.",
  },
  { id: "tmp-failed-1", role: "user", content: "Can you check my sleep apnea evidence?", failed: true },
];
