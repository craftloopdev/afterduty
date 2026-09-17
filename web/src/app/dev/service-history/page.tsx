import { ThemeProvider } from "@/components/shell/ThemeProvider";
import { AppShell } from "@/components/shell/AppShell";
import { ServiceHistoryView } from "@/components/profile/ServiceHistoryView";
import { profileFixture } from "@/lib/fixtures/profile";

// Dev preview of the Service History screen (dev routes 404 in production).
// Uses the reconciled profile fixture — one Navy-style reconciled Active period
// carrying two sources + reasoning, a still-running Guard period, and a manual
// row — exercising the conclusion rows + the evidence/reasoning modal.
export default function DevServiceHistoryPage() {
  return (
    <ThemeProvider>
      <AppShell userName="Griff" branch="U.S. Army · SGT" subState="free">
        <ServiceHistoryView periods={profileFixture.servicePeriods} />
      </AppShell>
    </ThemeProvider>
  );
}
