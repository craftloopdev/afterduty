"use client";

import ReactMarkdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";
import { parseCiteHref } from "@/lib/cite";
import { CitationChip } from "./CitationChip";
import styles from "./Markdown.module.css";

// Safe markdown renderer for assistant messages (spec §G.4). react-markdown
// renders NO raw HTML by default (XSS-safe for model output). The component map
// pins output to token-styled elements; model headings (#, ##, …) are demoted
// to styled text so they can't hijack the page outline. The link renderer
// intercepts the `cite:` scheme (§G.5) → CitationChip; other links open safely
// in a new tab; unknown cite: forms degrade to plain text.

function childText(children: React.ReactNode): string {
  if (typeof children === "string") return children;
  if (Array.isArray(children)) return children.map(childText).join("");
  return "";
}

const components: Components = {
  p: ({ children }) => <p className={styles.p}>{children}</p>,
  ul: ({ children }) => <ul className={styles.ul}>{children}</ul>,
  ol: ({ children }) => <ol className={styles.ol}>{children}</ol>,
  li: ({ children }) => <li className={styles.li}>{children}</li>,
  strong: ({ children }) => <strong className={styles.strong}>{children}</strong>,
  em: ({ children }) => <em>{children}</em>,
  code: ({ children }) => <code className={styles.code}>{children}</code>,
  pre: ({ children }) => <pre className={styles.pre}>{children}</pre>,
  blockquote: ({ children }) => <blockquote className={styles.quote}>{children}</blockquote>,
  hr: () => <hr className={styles.hr} />,
  // Demote every heading level to a single styled "lead" line.
  h1: ({ children }) => <p className={styles.heading}>{children}</p>,
  h2: ({ children }) => <p className={styles.heading}>{children}</p>,
  h3: ({ children }) => <p className={styles.heading}>{children}</p>,
  h4: ({ children }) => <p className={styles.heading}>{children}</p>,
  h5: ({ children }) => <p className={styles.heading}>{children}</p>,
  h6: ({ children }) => <p className={styles.heading}>{children}</p>,
  a: ({ href, children }) => {
    const cite = parseCiteHref(href);
    if (cite) return <CitationChip target={cite} label={childText(children)} />;
    // Only allow http(s) fall-through links; everything else (cite: that didn't
    // classify, javascript:, data:, no href) degrades to plain text.
    if (href && /^https?:\/\//i.test(href)) {
      return (
        <a className={styles.link} href={href} target="_blank" rel="noopener noreferrer">
          {children}
        </a>
      );
    }
    return <>{children}</>;
  },
};

// react-markdown's default urlTransform drops links with non-http(s) schemes,
// which would strip our `cite:` hrefs before the link renderer sees them. We
// pass through the raw url and validate the scheme ourselves in the `a`
// renderer (cite: → chip, http(s) → safe new-tab link, anything else → text).
function passthroughUrl(url: string): string {
  return url;
}

export function Markdown({ children }: { children: string }) {
  return (
    <div className={styles.md}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={components}
        urlTransform={passthroughUrl}
      >
        {children}
      </ReactMarkdown>
    </div>
  );
}
