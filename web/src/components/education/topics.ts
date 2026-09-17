import type { IconName } from "@/components/ui/Icon";

// The /learn hub's topic index (P1-18). Static — each slug is a real folder
// route under app/(app)/learn/ (no dynamic segment, so the native static
// export ships every page without generateStaticParams).

export interface LearnTopic {
  slug: string;
  title: string;
  blurb: string;
  icon: IconName;
}

export const LEARN_TOPICS: LearnTopic[] = [
  {
    slug: "how-va-rates",
    title: "How VA rates disabilities",
    blurb: "The three things every claim needs, in plain words.",
    icon: "target",
  },
  {
    slug: "intent-to-file",
    title: "Intent to File — lock in your date",
    blurb: "One free form can mean months of back pay.",
    icon: "flag",
  },
  {
    slug: "cp-exam",
    title: "Getting ready for your C&P exam",
    blurb: "What happens, what to bring, and why your worst days matter.",
    icon: "medical",
  },
  {
    slug: "vso",
    title: "Free help: VSOs",
    blurb: "Accredited reps who file claims with you — always free.",
    icon: "person",
  },
  {
    slug: "records",
    title: "Gathering your records",
    blurb: "DD-214, treatment records, buddy statements — how to get each one.",
    icon: "doc2",
  },
];
