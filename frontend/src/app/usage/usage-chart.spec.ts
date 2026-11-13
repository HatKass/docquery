import { TestBed } from '@angular/core/testing';
import { REPORT } from './testing/usage-fixtures';
import { UsageChart } from './usage-chart';

describe('UsageChart', () => {
  async function render() {
    const fixture = TestBed.createComponent(UsageChart);
    fixture.componentRef.setInput('daily', REPORT.daily);
    await fixture.whenStable();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  it('draws one bar per day with data, scaled to the maximum', async () => {
    const { el } = await render();
    const bars = el.querySelectorAll('path');
    expect(bars).toHaveLength(2);
    expect(el.querySelector('[data-testid="chart-title"]')?.textContent).toContain(
      'Estimated cost per day',
    );
    expect(el.textContent).toContain('max $0.0030');
  });

  it('switches the measure without a second axis', async () => {
    const { fixture, el } = await render();
    const answers = Array.from(el.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('Answers'),
    )!;
    answers.click();
    await fixture.whenStable();

    expect(el.querySelector('[data-testid="chart-title"]')?.textContent).toContain(
      'Answers per day',
    );
    expect(el.textContent).toContain('max 2');
    expect(answers.getAttribute('aria-pressed')).toBe('true');
  });

  it('shows a tooltip for the hovered or focused day', async () => {
    const { fixture, el } = await render();
    const targets = el.querySelectorAll('rect');
    targets[1].dispatchEvent(new Event('focus'));
    await fixture.whenStable();

    const tooltip = el.querySelector('[data-testid="chart-tooltip"]')!;
    expect(tooltip.textContent).toContain('2026-11-13');
    expect(tooltip.textContent).toContain('$0.0012');
    targets[1].dispatchEvent(new Event('blur'));
    await fixture.whenStable();
    expect(el.querySelector('[data-testid="chart-tooltip"]')).toBeNull();
  });

  it('offers the same numbers as a table', async () => {
    const { el } = await render();
    const rows = el.querySelectorAll('[data-testid="chart-table"] tbody tr');
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).toContain('12k');
  });
});
