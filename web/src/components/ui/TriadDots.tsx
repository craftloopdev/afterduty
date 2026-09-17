import { statusColors, resolveStatusIcon, type TriadLevel } from "@/lib/theme/tokens";
import { Icon } from "./Icon";
import styles from "./TriadDots.module.css";

interface TriadDotsProps {
  triad: { dx: TriadLevel; is: TriadLevel; nx: TriadLevel };
  size?: "sm" | "lg";
}

// P1-26: status is never conveyed by color alone. Each pill carries the
// resolveStatusIcon GLYPH (check / alert / dash — distinct shapes), and a
// visually-hidden "Diagnosis: Strong" prefix names the leg + level for
// screen readers wherever the dots appear (tables included).
const LEGS: { key: "dx" | "is" | "nx"; abbr: string; name: string }[] = [
  { key: "dx", abbr: "Dx", name: "Diagnosis" },
  { key: "is", abbr: "IS", name: "In-Service" },
  { key: "nx", abbr: "Nx", name: "Nexus" },
];

export function TriadDots({ triad, size = "sm" }: TriadDotsProps) {
  return (
    <div className={[styles.row, styles[size]].join(" ")}>
      {LEGS.map(({ key, abbr, name }) => {
        const level = triad[key];
        const c = statusColors(level);
        const label = `${name}: ${c.label}`;
        return (
          <span
            key={key}
            className={styles.pill}
            title={label}
            style={{ background: c.bg, color: c.fg }}
          >
            <Icon name={resolveStatusIcon(level)} size={size === "lg" ? 12 : 10} stroke={3} />
            <span aria-hidden="true">{abbr}</span>
            <span className="visually-hidden">{label}</span>
          </span>
        );
      })}
    </div>
  );
}
