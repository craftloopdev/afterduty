import { ThemeProvider } from "@/components/shell/ThemeProvider";
import { AppShell } from "@/components/shell/AppShell";
import { ShareManager } from "@/components/share/ShareManager";
import { sharesFixture } from "@/lib/fixtures/shares";

export default function DevSharePage() {
  return (
    <ThemeProvider>
      <AppShell userName="Griff" branch="U.S. Army · SGT" subState="free">
        <ShareManager shares={sharesFixture} />
      </AppShell>
    </ThemeProvider>
  );
}
