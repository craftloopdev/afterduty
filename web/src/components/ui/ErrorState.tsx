"use client";

import { Icon } from "./Icon";
import { Button } from "./Button";
import styles from "./ErrorState.module.css";

interface ErrorStateProps {
  title?: string;
  body?: string;
  onRetry?: () => void;
}

export function ErrorState({ title = "Something went wrong", body, onRetry }: ErrorStateProps) {
  return (
    <div className={styles.root}>
      <span className={styles.iconCircle}>
        <Icon name="alert" size={26} />
      </span>
      <h3 className={styles.title}>{title}</h3>
      {body && <p className={styles.body}>{body}</p>}
      {onRetry && (
        <Button variant="ghost" size="sm" icon="bolt" onClick={onRetry}>
          Try again
        </Button>
      )}
    </div>
  );
}
