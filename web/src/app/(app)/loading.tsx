import { Skeleton } from "@/components/ui/Skeleton";
import styles from "./loading.module.css";

export default function HomeLoading() {
  return (
    <div className={styles.wrap} aria-busy="true" aria-label="Loading">
      <Skeleton height={150} radius="var(--r-lg)" />
      <div className={styles.grid}>
        <div className={styles.col}>
          <Skeleton height={26} width="38%" />
          <Skeleton height={92} radius="var(--r-md)" />
          <Skeleton height={60} radius="var(--r-md)" />
          <Skeleton height={26} width="38%" />
          <Skeleton height={240} radius="var(--r-md)" />
        </div>
        <div className={styles.col}>
          <Skeleton height={200} radius="var(--r-lg)" />
          <Skeleton height={170} radius="var(--r-lg)" />
        </div>
      </div>
    </div>
  );
}
