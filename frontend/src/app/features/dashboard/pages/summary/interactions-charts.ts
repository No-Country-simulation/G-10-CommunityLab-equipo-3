import { Component, DestroyRef, ElementRef, afterNextRender, computed, effect, inject, signal, untracked } from '@angular/core';
import { INTERACTION_SOURCES, InteractionSource } from '../../../../core/api/api.models';
import { I18n } from '../../../../core/i18n/i18n.service';
import { TranslatePipe } from '../../../../core/i18n/translate.pipe';
import { SOURCE_ICON } from '../../../../core/ui-maps';
import { WorkspaceStore } from '../../../../core/workspace.store';
import { CountUp } from '../../../../shared/count-up.directive';

export type Range = 'daily' | 'weekly' | 'monthly';

/** How many buckets each range shows: 14 days, 8 weeks, 6 months */
const BUCKETS: Record<Range, number> = { daily: 14, weekly: 8, monthly: 6 };

type Counts = Record<InteractionSource, number>;

interface Bucket {
  /** Axis label (short) and tooltip/table label (long) */
  label: string;
  longLabel: string;
  counts: Counts;
}

/** Start of the bucket containing `d` (local time); weeks start on Monday */
function bucketStart(d: Date, range: Range): Date {
  const s = new Date(d.getFullYear(), d.getMonth(), range === 'monthly' ? 1 : d.getDate());
  if (range === 'weekly') s.setDate(s.getDate() - ((s.getDay() + 6) % 7));
  return s;
}

function previous(start: Date, range: Range, steps: number): Date {
  const d = new Date(start);
  if (range === 'monthly') d.setMonth(d.getMonth() - steps);
  else d.setDate(d.getDate() - steps * (range === 'weekly' ? 7 : 1));
  return d;
}

/** Clean axis maximum (1, 2, 5 × 10ⁿ) and its ticks */
function niceTicks(max: number): number[] {
  if (max <= 0) return [0];
  const step0 = max / 4;
  const mag = 10 ** Math.floor(Math.log10(step0));
  const step = Math.max(1, [1, 2, 5, 10].map((m) => m * mag).find((s) => s >= step0) ?? mag * 10);
  const top = Math.ceil(max / step) * step;
  return Array.from({ length: Math.round(top / step) + 1 }, (_, i) => i * step);
}

const emptyCounts = (): Counts => Object.fromEntries(INTERACTION_SOURCES.map((s) => [s, 0])) as Counts;

/**
 * Summary charts: interactions per network (part-to-whole of two) and interactions over time
 * (stacked columns by network), scoped by one Daily / Weekly / Monthly control above both.
 *
 * Interactions: the legend toggles and emphasizes a network; a column click scopes the network card to
 * that period (Esc clears); arrows move between columns; a new live message makes the current column pulse.
 * Colors validated with the dataviz palette checker for light and dark surfaces (see the styles).
 */
@Component({
  selector: 'app-interactions-charts',
  imports: [TranslatePipe, CountUp],
  templateUrl: './interactions-charts.html',
  styleUrl: './interactions-charts.css',
  host: { '(document:keydown.escape)': 'selected.set(null)' },
})
export class InteractionsCharts {
  private readonly store = inject(WorkspaceStore);
  private readonly i18n = inject(I18n);
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);

  protected readonly sources = INTERACTION_SOURCES;
  protected readonly sourceIcon = SOURCE_ICON;
  protected readonly ranges: Range[] = ['daily', 'weekly', 'monthly'];

  protected readonly range = signal<Range>('daily');
  /** Column under the pointer or keyboard focus (tooltip) */
  protected readonly active = signal<number | null>(null);
  /** Column clicked: the network card shows that period */
  protected readonly selected = signal<number | null>(null);
  /** Networks switched off from the legend */
  protected readonly hidden = signal<ReadonlySet<InteractionSource>>(new Set());
  /** Network hovered in a legend: the other one fades */
  protected readonly emphasis = signal<InteractionSource | null>(null);
  /** False for a frame after a range change, so the columns grow from the baseline */
  protected readonly grown = signal(false);
  /** Column that just received a live message */
  protected readonly pulse = signal<number | null>(null);
  protected readonly showTable = signal(false);

  /** The backend is still sending the history of the selected network (spec 008) */
  protected readonly loadingHistory = computed(() => this.store.historyLoading());

  protected readonly visible = computed(() => INTERACTION_SOURCES.filter((s) => !this.hidden().has(s)));

  /** Buckets of the selected range plus, in front, the same number of buckets before it (for the delta) */
  private readonly series = computed(() => {
    const range = this.range();
    const n = BUCKETS[range];
    const current = bucketStart(new Date(), range);
    const starts = Array.from({ length: n * 2 }, (_, i) => previous(current, range, n * 2 - 1 - i));
    const counts = starts.map(() => emptyCounts());
    const index = new Map(starts.map((s, i) => [s.getTime(), i]));
    for (const it of this.store.interactions()) {
      const at = new Date(it.at);
      if (Number.isNaN(at.getTime())) continue;
      const i = index.get(bucketStart(at, range).getTime());
      if (i !== undefined) counts[i][it.source]++;
    }
    return { starts, counts, n };
  });

  protected readonly buckets = computed<Bucket[]>(() => {
    const range = this.range();
    const { starts, counts, n } = this.series();
    const locale = this.i18n.locale();
    const short = new Intl.DateTimeFormat(locale, range === 'monthly' ? { month: 'short' } : { day: 'numeric', month: 'short' });
    const long = new Intl.DateTimeFormat(locale, range === 'monthly' ? { month: 'long', year: 'numeric' } : { day: 'numeric', month: 'long' });
    return starts.slice(n).map((start, i) => ({
      label: short.format(start),
      longLabel: range === 'weekly' ? this.i18n.t('summary.charts.weekOf', { date: long.format(start) }) : long.format(start),
      counts: counts[n + i],
    }));
  });

  protected total(counts: Counts) {
    return this.visible().reduce((acc, s) => acc + counts[s], 0);
  }

  protected readonly ticks = computed(() => niceTicks(Math.max(0, ...this.buckets().map((b) => this.total(b.counts)))));
  protected readonly axisMax = computed(() => this.ticks().at(-1) || 1);

  /** The tallest column, labeled on its cap (the one direct label of the chart) */
  protected readonly peak = computed(() => {
    let best = -1;
    let value = 0;
    this.buckets().forEach((b, i) => {
      const t = this.total(b.counts);
      if (t > value) [best, value] = [i, t];
    });
    return best;
  });

  /** Network card: the whole period, or the clicked column */
  protected readonly split = computed(() => {
    const sel = this.selected();
    const buckets = this.buckets();
    const counts = sel !== null && buckets[sel] ? buckets[sel].counts : this.sum(buckets.map((b) => b.counts));
    const all = this.total(counts);
    return {
      all,
      scope: sel !== null && buckets[sel] ? buckets[sel].longLabel : null,
      rows: INTERACTION_SOURCES.map((s) => {
        const off = this.hidden().has(s);
        return { source: s, value: counts[s], off, share: all && !off ? counts[s] / all : 0 };
      }),
    };
  });

  /** Change against the same number of buckets just before the period (visible networks only) */
  protected readonly delta = computed(() => {
    const { counts, n } = this.series();
    const before = this.total(this.sum(counts.slice(0, n)));
    const now = this.total(this.sum(counts.slice(n)));
    if (!before) return null;
    const change = (now - before) / before;
    return { change, text: new Intl.NumberFormat(this.i18n.locale(), { style: 'percent', maximumFractionDigits: 0 }).format(Math.abs(change)) };
  });

  /** Width of the plot, to space the axis labels so they never collide */
  private readonly plotWidth = signal(600);

  /** Show one label every `axisStep` columns (counted from the latest), as many as fit at ~48px each */
  protected readonly axisStep = computed(() => Math.max(1, Math.ceil((BUCKETS[this.range()] * 48) / this.plotWidth())));

  protected showAxisLabel(i: number) {
    return (BUCKETS[this.range()] - 1 - i) % this.axisStep() === 0;
  }

  constructor() {
    const destroyRef = inject(DestroyRef);
    afterNextRender(() => {
      const plot = this.host.nativeElement.querySelector<HTMLElement>('.plot');
      if (!plot) return;
      // Measure right away; the observer then follows later changes (sidebar collapse, rotation…)
      this.plotWidth.set(plot.clientWidth || this.plotWidth());
      if (typeof ResizeObserver === 'undefined') return;
      const observer = new ResizeObserver(([entry]) => this.plotWidth.set(entry.contentRect.width));
      observer.observe(plot);
      destroyRef.onDestroy(() => observer.disconnect());
    });

    // Grow the columns from the baseline on load and on every range change.
    // A timer, not requestAnimationFrame: rAF never fires in a background tab and the columns would stay at 0.
    effect((onCleanup) => {
      this.range();
      untracked(() => this.grown.set(false));
      const timer = setTimeout(() => this.grown.set(true), 40);
      onCleanup(() => clearTimeout(timer));
    });

    // A new live message: the current column pulses (it is always the latest one)
    let seen = -1;
    let timer: ReturnType<typeof setTimeout> | undefined;
    effect(() => {
      const count = this.store.interactions().length;
      if (seen >= 0 && count > seen) {
        clearTimeout(timer);
        untracked(() => this.pulse.set(BUCKETS[this.range()] - 1));
        timer = setTimeout(() => this.pulse.set(null), 1600);
      }
      seen = count;
    });
    destroyRef.onDestroy(() => clearTimeout(timer));
  }

  protected setRange(r: Range) {
    this.range.set(r);
    this.active.set(null);
    this.selected.set(null);
  }

  /** Legend click: hide or show a network; at least one stays visible */
  protected toggle(source: InteractionSource) {
    const next = new Set(this.hidden());
    if (next.has(source)) next.delete(source);
    else if (this.visible().length > 1) next.add(source);
    this.hidden.set(next);
  }

  protected select(i: number) {
    this.selected.set(this.selected() === i ? null : i);
  }

  /** Arrows, Home and End move between columns; Enter/Space select (native button) */
  protected onKey(event: KeyboardEvent, i: number) {
    const last = this.buckets().length - 1;
    const target = { ArrowRight: i + 1, ArrowLeft: i - 1, Home: 0, End: last }[event.key];
    if (target === undefined) return;
    event.preventDefault();
    const next = Math.max(0, Math.min(last, target));
    this.host.nativeElement.querySelectorAll<HTMLElement>('.band')[next]?.focus();
    // The tooltip follows the keyboard even if the focus event is late (e.g. window not focused)
    this.active.set(next);
  }

  protected pct(share: number) {
    return new Intl.NumberFormat(this.i18n.locale(), { style: 'percent', maximumFractionDigits: 0 }).format(share);
  }

  private sum(list: Counts[]): Counts {
    const out = emptyCounts();
    for (const c of list) for (const s of INTERACTION_SOURCES) out[s] += c[s];
    return out;
  }
}
