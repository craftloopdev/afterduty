"use client";

import { useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { Icon } from "@/components/ui/Icon";
import { Button } from "@/components/ui/Button";
import { uploadEvidence } from "@/lib/api/mutations";
import { armAnalysisPulse } from "@/lib/jobs";
import { tapLight, notifySuccess, notifyError } from "@/lib/native/haptics";
import styles from "./UploadCard.module.css";

// Extraction-supported types only (P1-28). The backend STORES more (gif/tiff/
// bmp/heif/doc/docx/xls…) but the AI pipeline can only read PDF, PNG, JPEG,
// WEBP, HEIC (SinglePassExtractionService.inlineMimeType) and plain text —
// anything else silently degrades to base64 garbage in the text path. Word
// files get an explicit pre-upload rejection instead (see WORD_RE below).
const ACCEPT = ".pdf,.png,.jpg,.jpeg,.webp,.heic,.txt";

// .doc/.docx: the backend accepts and stores them but the extraction pipeline
// has no Word reader — the file body reaches the model as base64 text and the
// analysis is garbage. Reject before upload with actionable copy.
const WORD_RE = /\.docx?$/i;
export const WORD_REJECT_MSG =
  "Word documents can't be read by our AI yet — please save it as a PDF and upload that instead.";

// Pull a human-readable message out of an error body when the server sent one
// (Spring puts it in `message`). Returns null for anything else.
async function serverMessage(res: Response): Promise<string | null> {
  try {
    const body: unknown = await res.json();
    if (body && typeof body === "object" && "message" in body) {
      const m = (body as { message?: unknown }).message;
      if (typeof m === "string" && m.trim()) return m;
    }
  } catch {
    // Non-JSON body — fall through to the generic message.
  }
  return null;
}

async function failureMessage(res: Response): Promise<string> {
  if (res.status === 413) return "That file is too large.";
  if (res.status === 409) return "That file was already uploaded.";
  if (res.status === 401) return "Please sign in again.";
  // Uploads are free — there is no paywall here. Anything unexpected
  // (including a stray 402) is a plain error; prefer the server's words
  // (e.g. the multipart path's 400 "File is too large. Maximum … 50 MB.").
  return (await serverMessage(res)) ?? "Upload failed. Please try again.";
}

type FileState = "queued" | "uploading" | "done" | "failed";
interface FileEntry {
  key: string;
  name: string;
  state: FileState;
  /** Per-file failure copy (413/409/401/server message/Word rejection). */
  error: string | null;
}

const STATE_LABEL: Record<FileState, string> = {
  queued: "Waiting…",
  uploading: "Uploading…",
  done: "Uploaded",
  failed: "Failed",
};

let keySeq = 0;

/**
 * `onUploaded` fires after at least one successful upload — the native pages
 * pass their loader's refetch so new documents appear without a manual reload
 * (router.refresh() is a no-op on the static export).
 */
export function UploadCard({ onUploaded }: { onUploaded?: () => void }) {
  const router = useRouter();
  const inputRef = useRef<HTMLInputElement>(null);
  const cameraRef = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const [entries, setEntries] = useState<FileEntry[]>([]);

  const patch = (key: string, up: Partial<FileEntry>) =>
    setEntries((prev) => prev.map((e) => (e.key === key ? { ...e, ...up } : e)));

  // One pass over a picked/dropped batch: every file gets its own row and its
  // own outcome — one bad file never blocks or hides the others (P1-28).
  const uploadFiles = async (files: File[]) => {
    if (files.length === 0 || busy) return;
    setBusy(true);

    const batch = files.map((file) => {
      const rejected = WORD_RE.test(file.name);
      return {
        file,
        rejected,
        entry: {
          key: `f${++keySeq}`,
          name: file.name,
          state: rejected ? ("failed" as const) : ("queued" as const),
          error: rejected ? WORD_REJECT_MSG : null,
        },
      };
    });
    // Replace the previous batch's list — statuses always describe THIS batch.
    setEntries(batch.map((b) => b.entry));

    let anyOk = false;
    let anyFailed = batch.some((b) => b.rejected);
    for (const b of batch) {
      if (b.rejected) continue;
      patch(b.entry.key, { state: "uploading" });
      try {
        const res = await uploadEvidence(b.file);
        if (res.ok) {
          anyOk = true;
          patch(b.entry.key, { state: "done" });
        } else {
          anyFailed = true;
          patch(b.entry.key, { state: "failed", error: await failureMessage(res) });
        }
      } catch {
        anyFailed = true;
        patch(b.entry.key, { state: "failed", error: "Upload failed. Please try again." });
      }
    }

    if (anyOk) {
      notifySuccess();
      // Arm the cross-screen analysis banner: the upload just started (or
      // will start) pipeline work, and Home/Steps must say so (P0-5).
      armAnalysisPulse();
      router.refresh();
      onUploaded?.();
    }
    if (anyFailed) notifyError();

    setBusy(false);
    if (inputRef.current) inputRef.current.value = "";
    if (cameraRef.current) cameraRef.current.value = "";
  };

  const onPick = (e: React.ChangeEvent<HTMLInputElement>) => {
    void uploadFiles(Array.from(e.target.files ?? []));
  };

  const onDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    if (busy) return;
    void uploadFiles(Array.from(e.dataTransfer?.files ?? []));
  };

  return (
    <div
      className={[styles.card, dragOver && styles.dragOver].filter(Boolean).join(" ")}
      onDragOver={(e) => {
        e.preventDefault();
        setDragOver(true);
      }}
      onDragLeave={() => setDragOver(false)}
      onDrop={onDrop}
    >
      <input ref={inputRef} type="file" hidden multiple onChange={onPick} accept={ACCEPT} />
      {/* Camera capture (mobile only — the button is display:none on fine
          pointers). No `multiple`: capture yields one photo per shot. */}
      <input ref={cameraRef} type="file" hidden capture="environment" accept="image/*" onChange={onPick} />
      <span className={styles.ic}>
        <Icon name="upload" size={24} stroke={2.1} />
      </span>
      <div className={styles.tx}>
        <b>Upload documents</b>
        <small>Medical &amp; service records, buddy letters, nexus letters — or drag files here</small>
      </div>
      <div className={styles.actions}>
        <Button
          variant="ghost"
          className={styles.cameraBtn}
          disabled={busy}
          onClick={() => {
            tapLight();
            cameraRef.current?.click();
          }}
        >
          Take photo
        </Button>
        <Button
          variant="primary"
          icon="plus"
          loading={busy}
          onClick={() => {
            tapLight();
            inputRef.current?.click();
          }}
        >
          Upload
        </Button>
      </div>
      {entries.length > 0 && (
        <ul className={styles.fileList} aria-live="polite">
          {entries.map((f) => (
            <li key={f.key} className={styles.fileRow}>
              <span className={styles.fileName} title={f.name}>
                {f.name}
              </span>
              <span
                className={[
                  styles.fileState,
                  f.state === "done" && styles.fileOk,
                  f.state === "failed" && styles.fileFail,
                ]
                  .filter(Boolean)
                  .join(" ")}
              >
                {f.state === "done" && <Icon name="check" size={13} stroke={3} />}
                {f.state === "failed" && <Icon name="alert" size={13} stroke={2.4} />}
                {STATE_LABEL[f.state]}
              </span>
              {f.error && (
                <span className={styles.fileError} role="alert">
                  {f.error}
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
