import type { CSSProperties } from "react";
import styles from "./Skeleton.module.css";

interface SkeletonProps {
  width?: string | number;
  height?: string | number;
  radius?: string | number;
  lines?: number;
  className?: string;
}

const dim = (v: string | number | undefined): string | undefined =>
  typeof v === "number" ? `${v}px` : v;

export function Skeleton({ width, height, radius, lines, className }: SkeletonProps) {
  const barStyle: CSSProperties = {
    width: dim(width) ?? "100%",
    height: dim(height) ?? ".9rem",
    borderRadius: dim(radius) ?? "var(--r-sm)",
  };

  if (lines && lines > 1) {
    const wrapCls = [styles.stack, className].filter(Boolean).join(" ");
    return (
      <div className={wrapCls} aria-hidden="true">
        {Array.from({ length: lines }, (_, i) => (
          <span
            key={i}
            className={styles.bar}
            style={i === lines - 1 ? { ...barStyle, width: "70%" } : barStyle}
          />
        ))}
      </div>
    );
  }

  const cls = [styles.bar, className].filter(Boolean).join(" ");
  return <span className={cls} style={barStyle} aria-hidden="true" />;
}
