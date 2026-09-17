import type { CSSProperties, ReactElement } from "react";

// Stroke-based icon set, ported from the redesign prototype (app/icons.jsx).
// Pure SVG → safe in Server Components. Color inherits via currentColor.
export type IconName =
  | "home"
  | "conditions"
  | "steps"
  | "target"
  | "person"
  | "plus"
  | "shield"
  | "upload"
  | "chat"
  | "sparkle"
  | "chevron"
  | "chevDown"
  | "back"
  | "file"
  | "check"
  | "checkCircle"
  | "alert"
  | "dash"
  | "close"
  | "share"
  | "money"
  | "info"
  | "lock"
  | "medical"
  | "flag"
  | "bolt"
  | "plus2"
  | "letter"
  | "pen"
  | "send"
  | "sign"
  | "doc2"
  | "clock"
  | "mail";

interface IconProps {
  name: IconName;
  size?: number;
  /** Stroke width — bump to ~2.3–2.6 for active/emphasis states. */
  stroke?: number;
  /** When set, the icon is exposed to AT with this label; otherwise aria-hidden. */
  title?: string;
  className?: string;
  style?: CSSProperties;
}

// `fill` icons set their own fill/stroke; everything else inherits from the <g>.
const FILL = { fill: "currentColor", stroke: "none" } as const;

const PATHS: Record<IconName, ReactElement> = {
  home: (
    <>
      <path d="M3 10.5 12 3l9 7.5" />
      <path d="M5 9.5V20h14V9.5" />
      <path d="M9.5 20v-5h5v5" />
    </>
  ),
  conditions: (
    <>
      <path d="M3.5 7l1.8 1.8L8.5 5.2" />
      <path d="M12 7h8.5" />
      <path d="M3.5 16l1.8 1.8L8.5 14.2" />
      <path d="M12 16h8.5" />
    </>
  ),
  steps: <path d="M5 21V5a1 1 0 0 1 1-1h10l-1.5 3L16 10H6" />,
  target: (
    <>
      <circle cx="12" cy="12" r="8.5" />
      <circle cx="12" cy="12" r="4.5" />
      <circle cx="12" cy="12" r="1.6" {...FILL} />
    </>
  ),
  person: (
    <>
      <circle cx="12" cy="8" r="4" />
      <path d="M4.5 20c.8-4 3.8-6 7.5-6s6.7 2 7.5 6" />
    </>
  ),
  plus: <path d="M12 5v14M5 12h14" />,
  // The brand mark ("Chevron A", branding/mark.svg) on the 24-unit grid: a
  // chevron with a bar forming an A, echoed twice below at 50% / 25%. The
  // name stays "shield" so the nine call sites are untouched.
  shield: (
    <>
      <path d="M8.6 10.7 12 7 15.4 10.7" />
      <path d="M9.4 10.4h5.2" />
      <path d="M8.6 15.6 12 11.9 15.4 15.6" opacity="0.5" />
      <path d="M8.6 20.5 12 16.8 15.4 20.5" opacity="0.25" />
    </>
  ),
  upload: (
    <>
      <path d="M12 16V4m0 0L8 8m4-4 4 4" />
      <path d="M5 15v3a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-3" />
    </>
  ),
  chat: <path d="M5 5h14a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H9l-4 4V6a1 1 0 0 1 1-1z" />,
  sparkle: (
    <>
      <path {...FILL} d="M12 3l1.7 4.8L18.5 9.5l-4.8 1.7L12 16l-1.7-4.8L5.5 9.5l4.8-1.7L12 3z" />
      <path {...FILL} opacity="0.7" d="M19 14l.7 2 .3.7 2 .3-2 .7-.3 2-.7-2-2-.3 2-.7.3-2z" />
    </>
  ),
  chevron: <path d="M9 5l7 7-7 7" />,
  chevDown: <path d="M5 9l7 7 7-7" />,
  back: <path d="M15 5l-7 7 7 7" />,
  file: (
    <>
      <path d="M7 3h7l4 4v14H7a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z" />
      <path d="M14 3v4h4" />
    </>
  ),
  check: <path d="M5 12.5l4.5 4.5L19 6.5" />,
  checkCircle: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M8 12.5l2.5 2.5L16 9.5" />
    </>
  ),
  alert: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7.5v5M12 16h0" />
    </>
  ),
  dash: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M8 12h8" />
    </>
  ),
  close: <path d="M6 6l12 12M18 6L6 18" />,
  share: (
    <>
      <circle cx="6" cy="12" r="2.5" />
      <circle cx="17" cy="6" r="2.5" />
      <circle cx="17" cy="18" r="2.5" />
      <path d="M8.2 10.8l6.6-3.6M8.2 13.2l6.6 3.6" />
    </>
  ),
  money: (
    <>
      <rect x="3" y="6" width="18" height="12" rx="2.5" />
      <circle cx="12" cy="12" r="2.5" />
      <path d="M6 9.5h0M18 14.5h0" />
    </>
  ),
  info: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 11v5M12 8h0" />
    </>
  ),
  lock: (
    <>
      <rect x="5" y="10" width="14" height="10" rx="2.5" />
      <path d="M8 10V7a4 4 0 0 1 8 0v3" />
    </>
  ),
  medical: (
    <>
      <rect x="4" y="6" width="16" height="14" rx="2.5" />
      <path d="M9 6V4.5A1.5 1.5 0 0 1 10.5 3h3A1.5 1.5 0 0 1 15 4.5V6" />
      <path d="M12 10.5v5M9.5 13h5" />
    </>
  ),
  flag: <path d="M6 21V4m0 1h11l-2 3.5L17 12H6" />,
  bolt: <path {...FILL} d="M13 2L4 13h6l-1 9 9-12h-6l1-8z" />,
  plus2: <path d="M12 6v12M6 12h12" />,
  letter: (
    <>
      <rect x="3" y="5" width="18" height="14" rx="2.5" />
      <path d="M4 7.5l8 5.5 8-5.5" />
    </>
  ),
  pen: (
    <>
      <path d="M14.5 4.5l5 5L9 20H4v-5L14.5 4.5z" />
      <path d="M12.5 6.5l5 5" />
    </>
  ),
  send: <path d="M4.5 11.5l15-6.5-6.5 15-2.4-6.1-6.1-2.4z" />,
  sign: (
    <>
      <path d="M3 16.5c2.5 0 2.7-8 5-8s2.2 5.5 4 5.5 1.8-2.5 3.5-2.5" />
      <path d="M3 20.5h18" />
    </>
  ),
  doc2: (
    <>
      <path d="M7 3h7l4 4v13a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z" />
      <path d="M14 3v4h4M9 12h6M9 16h6" />
    </>
  ),
  clock: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7.5V12l3 2" />
    </>
  ),
  mail: (
    <>
      <rect x="3" y="5" width="18" height="14" rx="2.5" />
      <path d="M4 7.5l8 5.5 8-5.5" />
    </>
  ),
};

export function Icon({ name, size = 24, stroke = 2, title, className, style }: IconProps) {
  return (
    <svg
      viewBox="0 0 24 24"
      width={size}
      height={size}
      className={className}
      style={style}
      role={title ? "img" : undefined}
      aria-label={title}
      aria-hidden={title ? undefined : true}
    >
      <g
        fill="none"
        stroke="currentColor"
        strokeWidth={stroke}
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        {PATHS[name]}
      </g>
    </svg>
  );
}
