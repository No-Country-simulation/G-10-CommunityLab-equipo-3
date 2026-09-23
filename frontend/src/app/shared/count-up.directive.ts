import { Directive, ElementRef, OnDestroy, effect, inject, input } from '@angular/core';

/**
 * Animates a formatted number ("48.2K", "1,284", "146 h", 312) from 0 to its value.
 * Keeps any prefix/suffix and the original decimals/grouping. Honors reduced motion.
 */
@Directive({ selector: '[appCountUp]' })
export class CountUp implements OnDestroy {
  readonly appCountUp = input.required<string | number>();
  readonly duration = input(900);

  private readonly el = inject<ElementRef<HTMLElement>>(ElementRef);
  private frame = 0;
  private current = 0;

  constructor() {
    effect(() => this.animate(String(this.appCountUp())));
  }

  ngOnDestroy() {
    cancelAnimationFrame(this.frame);
  }

  private animate(text: string) {
    const match = text.match(/^([^\d-]*)(-?[\d.,]+)(.*)$/);
    const reduced = typeof matchMedia !== 'undefined' && matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (!match || reduced) {
      this.el.nativeElement.textContent = text;
      return;
    }

    const [, prefix, raw, suffix] = match;
    const grouped = raw.includes(',');
    const clean = raw.replace(/,/g, '');
    const decimals = (clean.split('.')[1] ?? '').length;
    const target = parseFloat(clean);
    const from = this.current;
    const start = performance.now();

    cancelAnimationFrame(this.frame);
    const step = (now: number) => {
      const p = Math.min(1, (now - start) / this.duration());
      const eased = 1 - Math.pow(1 - p, 3);
      this.current = from + (target - from) * eased;
      const formatted = grouped
        ? this.current.toLocaleString('en-US', { minimumFractionDigits: decimals, maximumFractionDigits: decimals })
        : this.current.toFixed(decimals);
      this.el.nativeElement.textContent = prefix + formatted + suffix;
      if (p < 1) this.frame = requestAnimationFrame(step);
    };
    this.frame = requestAnimationFrame(step);
  }
}
