"use client";

import { loadProfilePage, useLoader } from "@/lib/api/endpoints.native";
import { ProfileView } from "@/components/profile/ProfileView";
import HomeLoading from "@/app/(app)/loading";
import { LoaderBoundary } from "./LoaderBoundary";

// Native Profile wrapper (§A.3). Account deletion goes through the mutations
// facade (direct `DELETE /auth/account` + driver signOut on native — §A.5/§H.3);
// only the profile load moves to useLoader.
export function NativeProfile() {
  const state = useLoader(loadProfilePage);
  return (
    <LoaderBoundary state={state} skeleton={<HomeLoading />}>
      {(profile) => <ProfileView profile={profile} />}
    </LoaderBoundary>
  );
}
