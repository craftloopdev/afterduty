import { EULA_URL, PRIVACY_URL } from "@/lib/constants";
import styles from "./LegalFooter.module.css";

// Canonical hosted legal URLs live in lib/constants.ts (the single source of
// truth shared with the native paywall — §C.3/§H.6.3). The Terms link is Apple's
// Standard EULA (the License Agreement the app ships under); the Privacy Policy
// is the standalone page at afterduty.app/privacy.
const TERMS_URL = EULA_URL;

/**
 * Legal footer for the auth screens: Terms / Privacy links plus the
 * "educational tool, not legal advice" disclaimer a veteran should see at the
 * moment they hand over PII. Plain footnote per the auth-review draft.
 */
export function LegalFooter() {
  return (
    <footer className={styles.legal}>
      <p className={styles.links}>
        <a href={TERMS_URL} target="_blank" rel="noopener noreferrer">
          Terms of Service
        </a>
        <span aria-hidden="true" className={styles.dot}>
          ·
        </span>
        <a href={PRIVACY_URL} target="_blank" rel="noopener noreferrer">
          Privacy Policy
        </a>
      </p>
      <p className={styles.disclaimer}>
        After Duty is an educational tool, not legal advice or a substitute for an accredited
        representative.
      </p>
    </footer>
  );
}
