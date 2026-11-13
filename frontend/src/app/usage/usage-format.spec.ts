import { formatCost, formatQuota, formatTokens } from './usage-format';

describe('usage formatting', () => {
  it('keeps tiny demo costs readable', () => {
    expect(formatCost(0)).toBe('$0');
    expect(formatCost(0.00042)).toBe('$0.00042');
    expect(formatCost(0.0123)).toBe('$0.01');
    expect(formatCost(12.345)).toBe('$12.35');
    expect(formatCost(null)).toBe('—');
  });

  it('abbreviates token counts', () => {
    expect(formatTokens(950)).toBe('950');
    expect(formatTokens(1234)).toBe('1.2k');
    expect(formatTokens(45_678)).toBe('46k');
    expect(formatTokens(4_500_000)).toBe('4.5M');
  });

  it('describes quotas with and without a limit', () => {
    expect(formatQuota(12, 50)).toBe('12 of 50');
    expect(formatQuota(12, null)).toBe('12 (no limit)');
  });
});
