/** "$0.0012", "$1.23": small demo costs stay readable instead of rounding to $0.00. */
export function formatCost(usd: number | null | undefined): string {
  if (usd === null || usd === undefined) {
    return '—';
  }
  if (usd === 0) {
    return '$0';
  }
  if (usd < 0.01) {
    return `$${usd.toPrecision(2)}`;
  }
  return `$${usd.toFixed(2)}`;
}

/** "950", "12.3k", "4.5M". */
export function formatTokens(tokens: number): string {
  if (tokens < 1000) {
    return `${tokens}`;
  }
  if (tokens < 1_000_000) {
    return `${(tokens / 1000).toFixed(tokens < 10_000 ? 1 : 0)}k`;
  }
  return `${(tokens / 1_000_000).toFixed(1)}M`;
}

/** "12 of 50" or "12 (no limit)". */
export function formatQuota(used: number, limit: number | null): string {
  return limit === null ? `${used} (no limit)` : `${used} of ${limit}`;
}
