import "server-only";
import { cache } from "react";
import { serverFetch } from "./client";
import { makeEndpoints } from "./endpoints-core";

export type { SubscriptionResult, DocumentsData } from "./endpoints-core";

// Web entry for the loader seam (capacitor-ios-spec §A.2): bind every loader to
// the BFF `serverFetch` transport and inject React `cache` as the per-render
// dedupe strategy (getMe/getConditions/getGaps dedupe across the layout+page
// reads within one render, exactly as before this refactor — one /me even if
// read twice). `cache()` stays in this server-only file and is passed down; it
// must NOT leak into `endpoints-core.ts`. Signatures and behavior are unchanged,
// so existing RSC pages and api/ route handlers keep their imports.
const endpoints = makeEndpoints(serverFetch, cache);

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
