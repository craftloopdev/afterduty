import styles from "./RatingBadge.module.css";

interface RatingBadgeProps {
  value: number;
  sub?: string;
  dark?: boolean;
}

export function RatingBadge({ value, sub, dark }: RatingBadgeProps) {
  const cls = [styles.badge, dark && styles.dark].filter(Boolean).join(" ");
  return (
    <div className={cls}>
      <div className={styles.num}>
        {value}
        <span className={styles.pct}>%</span>
      </div>
      {sub && <div className={styles.sub}>{sub}</div>}
    </div>
  );
}
