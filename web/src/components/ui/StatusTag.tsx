import { statusColors, type TriadLevel } from "@/lib/theme/tokens";
import styles from "./StatusTag.module.css";

interface StatusTagProps {
  level: TriadLevel;
}

export function StatusTag({ level }: StatusTagProps) {
  const c = statusColors(level);
  return (
    <span className={styles.tag} style={{ background: c.bg, color: c.fg }}>
      <span className={styles.dot} style={{ background: c.dot }} aria-hidden="true" />
      {c.label}
    </span>
  );
}
