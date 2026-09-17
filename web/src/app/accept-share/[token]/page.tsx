import { AcceptShare } from "@/components/share/AcceptShare";

/**
 * Public landing page for share-invite links
 * (https://app.afterduty.app/accept-share/{token}). The preview itself goes
 * through the BFF, which answers 401 when the viewer isn't signed in — the
 * client then bounces to /login?next= and comes back here.
 *
 * This per-token dynamic route is canonical on WEB (the link recipients open).
 * It can't pre-render under `output:"export"`, so on NATIVE acceptance is reached
 * through the query-param twin `/accept-share?token=` (§A.3a) and this `[token]`
 * route is excluded from the export build by the native-export wrapper
 * (scripts/native-export.mjs). No `generateStaticParams` is needed or wanted —
 * adding one would force the web route into static-generation mode. Web stays a
 * pure on-demand dynamic page, byte-identical to baseline.
 */
export default async function AcceptSharePage({
  params,
}: {
  params: Promise<{ token: string }>;
}) {
  const { token } = await params;
  return <AcceptShare token={token} />;
}
