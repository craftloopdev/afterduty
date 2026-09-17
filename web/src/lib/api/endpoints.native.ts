// Native entry for the loader seam (capacitor-ios-spec §A.2). Binds every loader
// to the `directFetch` transport (Bearer → Spring, no BFF, no cookie). NO React
// `cache` is injected: each native screen mounts one `useLoader`, so the
// cross-render dedupe `cache()` gives the web layout+page reads isn't needed
// (§A.2). This file is client-safe — it must NEVER import `server-only`,
// `next/headers`, or React `cache` (the export build would break).
//
// The loader bodies are the SAME `makeEndpoints` surface the web entry uses, so
// the view-models the shared `*View` components render are identical on both
// targets — the only difference is the transport underneath.

import { directFetch } from "./direct";
import { makeEndpoints } from "./endpoints-core";

export type { SubscriptionResult, DocumentsData } from "./endpoints-core";
export { useLoader } from "@/lib/hooks/useLoader";

const endpoints = makeEndpoints(directFetch);

export const getMe = endpoints.getMe;
export const getConditions = endpoints.getConditions;
export const getGaps = endpoints.getGaps;
export const getNotifications = endpoints.getNotifications;
export const loadAnalysisUpdates = endpoints.loadAnalysisUpdates;
export const loadTimeline = endpoints.loadTimeline;
export const getUsage = endpoints.getUsage;
export const getSubscriptionResult = endpoints.getSubscriptionResult;
export const getSubscription = endpoints.getSubscription;
export const probeAnalysisBlocked = endpoints.probeAnalysisBlocked;
export const calculateCombined = endpoints.calculateCombined;
export const loadHomeVM = endpoints.loadHomeVM;
export const loadConditions = endpoints.loadConditions;
export const loadConditionsPage = endpoints.loadConditionsPage;
export const loadConditionDetail = endpoints.loadConditionDetail;
export const loadSubscription = endpoints.loadSubscription;
export const loadUsageBreakdown = endpoints.loadUsageBreakdown;
export const getProfile = endpoints.getProfile;
export const loadProfilePage = endpoints.loadProfilePage;
export const loadMessages = endpoints.loadMessages;
export const loadShares = endpoints.loadShares;
export const loadDocuments = endpoints.loadDocuments;
export const loadNextSteps = endpoints.loadNextSteps;
