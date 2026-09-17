import Link from "next/link";
import { Icon } from "@/components/ui/Icon";
import styles from "./learn.module.css";

// Article shell for the /learn topic pages (P1-18). Pure presentational RSC —
// no client hooks, no data loads, no server-only imports — so every topic page
// stays statically exportable for the native (Capacitor) build.

export function LearnArticle({
  title,
  lede,
  children,
}: {
  title: string;
  lede: string;
  children: React.ReactNode;
}) {
  return (
    <article className={styles.article}>
      <Link href="/learn" className={styles.back}>
        <Icon name="back" size={16} stroke={2.4} />
        All guides
      </Link>
      <h1 className={styles.h1}>{title}</h1>
      <p className={styles.lede}>{lede}</p>
      {children}
      <p className={styles.hedge}>
        After Duty is an educational tool, not legal advice. VA makes every rating decision
        after its own review and exams — nothing here predicts or promises an outcome.
      </p>
    </article>
  );
}

export function LearnSection({
  title,
  children,
  id,
}: {
  title: string;
  children: React.ReactNode;
  /** Optional anchor id so other screens can deep-link to this section (e.g. #pyramiding). */
  id?: string;
}) {
  return (
    <section className={styles.section} id={id}>
      <h2 className={styles.h2}>{title}</h2>
      {children}
    </section>
  );
}
