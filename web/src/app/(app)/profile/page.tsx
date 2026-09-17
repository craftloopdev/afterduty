import { loadProfilePage } from "@/lib/api/endpoints";
import { ProfileView } from "@/components/profile/ProfileView";
import { NATIVE } from "@/lib/platform";
import { NativeProfile } from "@/components/native/NativeProfile";

export default async function ProfilePage() {
  if (NATIVE) return <NativeProfile />;
  const profile = await loadProfilePage();
  return <ProfileView profile={profile} />;
}
