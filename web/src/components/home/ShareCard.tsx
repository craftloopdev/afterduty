import Link from "next/link";
import { Icon } from "@/components/ui/Icon";
import styles from "./ShareCard.module.css";

/**
 * "Share with your VSO" CTA card. Mirrors web.jsx `.w-sharecard`.
 * Pure presentational Server Component; links to the Share manager.
 * `docsOnly` is the free-tier variant: document sharing works without Pro, so
 * the copy promises documents — not the analysis a free user doesn't have.
 */
export function ShareCard({ docsOnly = false }: { docsOnly?: boolean }) {
  return (
    <div className={styles.card}>
      <span className={styles.iconChip}>
        <Icon name="share" size={20} stroke={2.1} />
      </span>
      <h3 className={styles.title}>Share with your VSO</h3>
      <p className={styles.body}>
        {docsOnly
          ? "Give an accredited rep secure access to the documents you've stored."
          : "Give an accredited rep secure access to review your conditions, ratings & gaps."}
      </p>
      <Link href="/share" className={styles.cta}>
        Create secure invite
      </Link>
    </div>
  );
}
