"use client";

import { Suspense } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { loadConditionDetail, useLoader } from "@/lib/api/endpoints.native";
import { ConditionDetail } from "@/components/conditions/ConditionDetail";
import { EmptyState } from "@/components/ui/EmptyState";
import { Icon } from "@/components/ui/Icon";
import HomeLoading from "@/app/(app)/loading";
import { LoaderBoundary } from "./LoaderBoundary";
import styles from "@/app/(app)/conditions/conditions.module.css";

// Native condition-detail wrapper for the query-param twin route (§A.3a). The
// dynamic `[id]` route can't pre-render per-user params under `output:"export"`,
// so native links resolve detail through `/conditions/detail?id=N`
// (`condHref()`). `useSearchParams()` reads the id client-side (wrapped in
// Suspense for the static-prerender CSR bailout). A missing/unknown id renders a
// friendly empty state rather than `notFound()` (no static page natively).
export function NativeConditionDetail() {
  return (
    <Suspense fallback={<HomeLoading />}>
      <ConditionDetailInner />
    </Suspense>
  );
}

function ConditionDetailInner() {
  const idParam = useSearchParams().get("id");
  const id = idParam == null ? NaN : Number(idParam);
  const state = useLoader(() =>
    Number.isFinite(id) ? loadConditionDetail(id) : Promise.resolve(null),
  );

  return (
    <LoaderBoundary state={state} skeleton={<HomeLoading />}>
      {(detail) =>
        detail == null ? (
          <EmptyState
            icon="conditions"
            title="Condition not found"
            body="This condition is no longer available."
            action={
              <Link className={styles.cta} href="/conditions">
                <Icon name="back" size={18} stroke={2.4} /> Back to conditions
              </Link>
            }
          />
        ) : (
          <div className={styles.detailWrap}>
            <Link href="/conditions" className={styles.back}>
              <Icon name="back" size={18} stroke={2.4} /> Conditions
            </Link>
            <ConditionDetail
              cond={detail.cond}
              relatedStep={detail.relatedStep}
              onChanged={state.refetch}
            />
          </div>
        )
      }
    </LoaderBoundary>
  );
}
