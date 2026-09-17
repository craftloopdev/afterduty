import { notFound } from "next/navigation";
import { ThemeProvider } from "@/components/shell/ThemeProvider";
import { AppShell } from "@/components/shell/AppShell";
import { HomeView } from "@/components/home/HomeView";
import { HOME_FIXTURES } from "@/lib/fixtures/home";

// Fixture keys are statically known, so this dynamic dev route CAN pre-render —
// required for the capture export build (`output:"export"`, §F.2), where the
// screenshot pipeline deep-links to `/dev/home/<state>`. Harmless on web (these
// dev routes 404 in production via the dev layout gate).
export function generateStaticParams(): { state: string }[] {
  return Object.keys(HOME_FIXTURES).map((state) => ({ state }));
}

// Renders the full Home (shell + content) for a named fixture state, no auth.
// e.g. /dev/home/populated | empty | analyzing | overflow
export default async function DevHomePage({
  params,
}: {
  params: Promise<{ state: string }>;
}) {
  const { state } = await params;
  const vm = HOME_FIXTURES[state];
  if (!vm) notFound();
  return (
    <ThemeProvider>
      <AppShell userName="Griff" branch="U.S. Army · SGT" subState={vm.subState}>
        <HomeView vm={vm} />
      </AppShell>
    </ThemeProvider>
  );
}
