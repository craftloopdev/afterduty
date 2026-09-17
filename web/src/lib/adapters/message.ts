import type { MessageResponse } from "@/lib/models/api";
import type { MessageVM } from "@/lib/models/vm";

export function toMessage(m: MessageResponse, i: number): MessageVM {
  const role = (m.role ?? "").toLowerCase() === "user" ? "user" : "assistant";
  return {
    id: String(m.id ?? `m-${i}`),
    role,
    content: (m.content ?? "").trim(),
    createdAt: m.createdAt,
  };
}
