import { notFound } from "next/navigation";
import { ThemeProvider } from "@/components/shell/ThemeProvider";
import { AppShell } from "@/components/shell/AppShell";
import { ConditionDetail } from "@/components/conditions/ConditionDetail";
import { populatedHomeVM } from "@/lib/fixtures/home";

// Fixture condition ids are statically known, so this dev route pre-renders for
// the capture export build (§F.2 — screenshots deep-link to /dev/conditions/1).
// Harmless on web (dev routes 404 in production via the dev layout gate).
export function generateStaticParams(): { id: string }[] {
  return populatedHomeVM.conditions.map((c) => ({ id: String(c.id) }));
}

export default async function DevConditionDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const cond = populatedHomeVM.conditions.find((c) => String(c.id) === id);
  if (!cond) notFound();
  const relatedStep = populatedHomeVM.steps.find((s) => s.condId === cond.id) ?? null;
  return (
    <ThemeProvider>
      <AppShell userName="Griff" branch="U.S. Army · SGT" subState="free">
        <ConditionDetail cond={cond} relatedStep={relatedStep} />
      </AppShell>
    </ThemeProvider>
  );
}
