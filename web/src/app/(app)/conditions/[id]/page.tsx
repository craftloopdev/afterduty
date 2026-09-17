import { notFound } from "next/navigation";
import Link from "next/link";
import { loadConditionDetail } from "@/lib/api/endpoints";
import { ConditionDetail } from "@/components/conditions/ConditionDetail";
import { Icon } from "@/components/ui/Icon";
import styles from "../conditions.module.css";

// This per-user dynamic route is the canonical detail page on WEB (dynamically
// server-rendered via the cookie-reading layout). It CANNOT pre-render under
// `output:"export"` (params depend on per-user server data), so on NATIVE detail
// is reached through the query-param twin `/conditions/detail?id=` (§A.3a) and
// this `[id]` route is excluded from the export build entirely by the
// native-export wrapper (scripts/native-export.mjs). It therefore needs NO
// `generateStaticParams` — and must not have one, or the web route would flip
// into static-generation mode and the layout's `cookies()` read would throw
// DYNAMIC_SERVER_USAGE. This keeps the web route byte-identical to baseline.
export default async function ConditionDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const detail = await loadConditionDetail(Number(id));
  if (!detail) notFound();
  return (
    <div className={styles.detailWrap}>
      <Link href="/conditions" className={styles.back}>
        <Icon name="back" size={18} stroke={2.4} /> Conditions
      </Link>
      <ConditionDetail cond={detail.cond} relatedStep={detail.relatedStep} />
    </div>
  );
}
