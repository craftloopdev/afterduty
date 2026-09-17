import Link from "next/link";
import { Icon } from "@/components/ui/Icon";
import { LEARN_TOPICS } from "@/components/education/topics";
import styles from "@/components/education/learn.module.css";

// The /learn hub (P1-18). All static content, $0 model spend, exportable in
// the native build (pure RSC, no server-only imports, no dynamic segments).

export const metadata = { title: "Learn — After Duty" };

export default function LearnPage() {
  return (
    <div className={styles.hub}>
      {/* No in-page header: the AppShell TopBar already titles this route. */}
      <ul className={styles.topicList}>
        {LEARN_TOPICS.map((t) => (
          <li key={t.slug}>
            <Link href={`/learn/${t.slug}`} className={styles.topicCard}>
              <span className={styles.topicIcon} aria-hidden="true">
                <Icon name={t.icon} size={22} stroke={2.1} />
              </span>
              <span className={styles.topicTx}>
                <span className={styles.topicTitle}>{t.title}</span>
                <span className={styles.topicBlurb}>{t.blurb}</span>
              </span>
              <span className={styles.topicChevron} aria-hidden="true">
                <Icon name="chevron" size={18} stroke={2.2} />
              </span>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}
