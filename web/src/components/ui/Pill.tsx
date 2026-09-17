import { pillColors, type PillTone } from "@/lib/theme/tokens";
import { Icon, type IconName } from "./Icon";
import styles from "./Pill.module.css";

interface PillProps {
  tone: PillTone;
  icon?: IconName;
  /** Multi-line pill for sentence-length content (e.g. a presumptive basis).
   *  The default nowrap pill overflows its card — and the page — on phones. */
  wrap?: boolean;
  children: React.ReactNode;
}

export function Pill({ tone, icon, wrap = false, children }: PillProps) {
  const c = pillColors(tone);
  const cls = [styles.pill, tone === "line" && styles.line, wrap && styles.wrap]
    .filter(Boolean)
    .join(" ");
  return (
    <span className={cls} style={{ color: c.fg, background: c.bg }}>
      {icon && <Icon name={icon} size={12} stroke={2.2} />}
      {children}
    </span>
  );
}
