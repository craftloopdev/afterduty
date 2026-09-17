import { ThemeProvider } from "@/components/shell/ThemeProvider";
import { AppShell } from "@/components/shell/AppShell";
import { DocumentsClient } from "@/components/documents/DocumentsClient";
import { docsFixture } from "@/lib/fixtures/documents";

export default function DevDocumentsPage() {
  return (
    <ThemeProvider>
      <AppShell userName="Griff" branch="U.S. Army · SGT" subState="free">
        {/* Preview the real Docs client so the relocated "Write a statement" +
            "Describe what you remember" sections (P2-1) render here too. */}
        <DocumentsClient initialDocs={docsFixture} subState="free" />
      </AppShell>
    </ThemeProvider>
  );
}
