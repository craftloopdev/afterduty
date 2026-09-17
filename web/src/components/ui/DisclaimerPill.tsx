"use client";

import { Icon } from "./Icon";
import styles from "./DisclaimerPill.module.css";

interface DisclaimerPillProps {
  onClick?: () => void;
}

export function DisclaimerPill({ onClick }: DisclaimerPillProps) {
  return (
    <button type="button" className={styles.pill} onClick={onClick} title="About this guidance">
      <Icon name="info" size={15} stroke={2.2} />
      Educational
    </button>
  );
}
