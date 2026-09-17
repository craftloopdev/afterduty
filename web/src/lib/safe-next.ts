/**
 * Sanitize a `?next=` redirect target so login can only deep-link within the
 * app: it must be a same-origin path ("/x..."), never protocol-relative
 * ("//evil.com" or "/\evil.com" — browsers treat "\" as "/") and never an
 * absolute URL. Anything else falls back to "/".
 */
export function safeNext(raw: string | null | undefined): string {
  if (raw && /^\/(?![/\\])/.test(raw)) return raw;
  return "/";
}
