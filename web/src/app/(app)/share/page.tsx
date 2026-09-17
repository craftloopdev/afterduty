import { loadShares } from "@/lib/api/endpoints";
import { ShareManager } from "@/components/share/ShareManager";
import { NATIVE } from "@/lib/platform";
import { NativeShare } from "@/components/native/NativeShare";

export default async function SharePage() {
  if (NATIVE) return <NativeShare />;
  const shares = await loadShares();
  return <ShareManager shares={shares} />;
}
