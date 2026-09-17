import styles from "./StrengthBar.module.css";

interface StrengthBarProps {
  ready: number;
  total: number;
}

export function StrengthBar({ ready, total }: StrengthBarProps) {
  if (total <= 0) return null;
  return (
    <div className={styles.bar} role="presentation">
      {Array.from({ length: total }, (_, i) => (
        <span key={i} className={[styles.seg, i < ready && styles.on].filter(Boolean).join(" ")} />
      ))}
    </div>
  );
}
