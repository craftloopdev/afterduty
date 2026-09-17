"use client";

import { ErrorState } from "@/components/ui/ErrorState";

// Next 16 error boundaries must be Client Components; recovery prop is `unstable_retry`.
export default function AppError({
  unstable_retry,
}: {
  error: Error & { digest?: string };
  unstable_retry: () => void;
}) {
  return (
    <ErrorState
      body="We couldn't load this page right now. Please try again."
      onRetry={() => unstable_retry()}
    />
  );
}
