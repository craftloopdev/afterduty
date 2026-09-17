import { ThemeProvider } from "@/components/shell/ThemeProvider";
import { AppShell } from "@/components/shell/AppShell";
import { ConditionsList } from "@/components/conditions/ConditionsList";
import { populatedHomeVM } from "@/lib/fixtures/home";

export default function DevConditionsPage() {
  return (
    <ThemeProvider>
      <AppShell userName="Griff" branch="U.S. Army · SGT" subState="free">
        <ConditionsList conditions={populatedHomeVM.conditions} />
      </AppShell>
    </ThemeProvider>
  );
}
