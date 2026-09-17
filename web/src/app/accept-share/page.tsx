import { notFound } from "next/navigation";
import { AcceptShare } from "@/components/share/AcceptShare";
import { NATIVE } from "@/lib/platform";
import { NativeAcceptShare } from "@/components/native/NativeAcceptShare";

// Query-param twin of `accept-share/[token]` (capacitor-ios-spec §A.3a). Native
// (static export) reaches share acceptance through `?token=` since dynamic
// params can't be pre-rendered; web keeps `/accept-share/[token]` canonical for
// the public invite links recipients open. Both render the same client
// `AcceptShare`. Native reads the token client-side (`useSearchParams`) because
// reading `searchParams` in an RSC is illegal under export; web keeps the RSC
// read so behavior is unchanged.
export default async function AcceptShareQueryPage({
  searchParams,
}: {
  searchParams: Promise<{ token?: string }>;
}) {
  if (NATIVE) return <NativeAcceptShare />;
  const { token } = await searchParams;
  if (!token) notFound();
  return <AcceptShare token={token} />;
}
