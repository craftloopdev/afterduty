"use client";

// Viewer-mode context (P0-8). The shell provides the current ViewerVM here so
// ANY downstream client component can ask `useViewer().viewing` and hide its
// mutation affordances (upload, delete, add, chat composer) without prop
// drilling. The backend enforces share scopes regardless — hiding is honesty,
// not security.

import { createContext, useContext } from "react";
import type { ViewerVM } from "@/lib/models/vm";

/** The default: the user is on their OWN claim — nothing is restricted. */
export const NOT_VIEWING: ViewerVM = {
  viewing: false,
  claimId: null,
  ownerName: null,
  canViewAnalysis: false,
  canUploadDocs: false,
  analysisBlocked: false,
};

const ViewerCtx = createContext<ViewerVM>(NOT_VIEWING);

export function ViewerProvider({
  value,
  children,
}: {
  value: ViewerVM;
  children: React.ReactNode;
}) {
  return <ViewerCtx.Provider value={value}>{children}</ViewerCtx.Provider>;
}

/**
 * The current viewer state. `viewing === true` means a SHARED claim is being
 * read — components must hide add/delete/edit affordances (and the chat
 * composer unless the share grants chat access).
 */
export function useViewer(): ViewerVM {
  return useContext(ViewerCtx);
}
