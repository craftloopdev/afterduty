"use client";

import { useEffect } from "react";
import { watchIdToken } from "@/lib/firebase/session";

/** Keeps the httpOnly session cookie fresh as Firebase rotates the ID token. */
export function SessionWatcher() {
  useEffect(() => watchIdToken(), []);
  return null;
}
