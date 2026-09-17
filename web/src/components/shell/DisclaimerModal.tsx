"use client";

import { Modal } from "@/components/ui/Modal";
import { Icon } from "@/components/ui/Icon";
import { Button } from "@/components/ui/Button";
import styles from "./DisclaimerModal.module.css";

interface DisclaimerModalProps {
  open: boolean;
  onClose: () => void;
}

export function DisclaimerModal({ open, onClose }: DisclaimerModalProps) {
  return (
    <Modal open={open} onClose={onClose} size="sm" ariaLabel="About this guidance">
      <div className={styles.body}>
        <div className={styles.shield}>
          <Icon name="shield" size={28} stroke={2} />
        </div>
        <h3 className={styles.h}>About this guidance</h3>
        <p className={styles.lead}>
          After Duty is an <b>AI-assisted educational tool</b>. It helps you organize evidence and
          understand how the VA may view your claim.
        </p>
        <ul className={styles.pts}>
          <li>
            <span className={`${styles.x} ${styles.y}`}>
              <Icon name="check" size={13} stroke={3} />
            </span>
            Organizes records &amp; spots evidence gaps
          </li>
          <li>
            <span className={`${styles.x} ${styles.y}`}>
              <Icon name="check" size={13} stroke={3} />
            </span>
            Estimates ratings using public VASRD criteria
          </li>
          <li>
            <span className={`${styles.x} ${styles.n}`}>
              <Icon name="close" size={13} stroke={3} />
            </span>
            Is <b>not</b> legal advice and does not file your claim
          </li>
          <li>
            <span className={`${styles.x} ${styles.n}`}>
              <Icon name="close" size={13} stroke={3} />
            </span>
            Does <b>not</b> replace a VSO or accredited attorney
          </li>
        </ul>
        <Button variant="primary" full onClick={onClose}>
          Got it
        </Button>
      </div>
    </Modal>
  );
}
