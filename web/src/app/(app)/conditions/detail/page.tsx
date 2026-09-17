import { notFound } from "next/navigation";
import Link from "next/link";
import { loadConditionDetail } from "@/lib/api/endpoints";
import { ConditionDetail } from "@/components/conditions/ConditionDetail";
import { Icon } from "@/components/ui/Icon";
import { NATIVE } from "@/lib/platform";
import { NativeConditionDetail } from "@/components/native/NativeConditionDetail";
import styles from "../conditions.module.css";

// Query-param twin of `(app)/conditions/[id]` (capacitor-ios-spec §A.3a).
// `output:"export"` can't pre-render per-user dynamic params, so native links
// resolve condition detail through `?id=` instead. On NATIVE this is the
// canonical detail route (client wrapper reads `?id=` + DirectApiClient); on web
// the `[id]` route stays canonical and this twin renders the identical page so
// both paths work — no forked logic.
export default async function ConditionDetailQueryPage({
  searchParams,
}: {
  searchParams: Promise<{ id?: string }>;
}) {
  if (NATIVE) return <NativeConditionDetail />;

  const { id } = await searchParams;
  if (id == null) notFound();
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
