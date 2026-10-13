import { Component, computed, inject, input, signal } from '@angular/core';
import { I18n } from '../../core/i18n/i18n.service';
import { expressionKey, expressionSrc } from '../../core/mascot';

/** True when the system asks for less motion: the animated bears then show a still frame */
function prefersReducedMotion() {
  const query = typeof matchMedia === 'function' ? matchMedia('(prefers-reduced-motion: reduce)') : null;
  const reduced = signal(query?.matches ?? false);
  query?.addEventListener('change', (e) => reduced.set(e.matches));
  return reduced.asReadonly();
}

/**
 * The mascot showing the expression the LLM picked, next to a short text.
 *   <app-mascot [expression]="a.origin.expression" />                → bear + "Celebrando" (in the UI language)
 *   <app-mascot [expression]="x" [label]="summary.overallSentiment" /> → bear + custom text
 *   <app-mascot [expression]="x" [showLabel]="false" [size]="96" />   → bear only
 * Unknown or missing labels fall back to the neutral bear.
 * Neutral, contento and triste (the sentiment bears) are animated WebP; the other expressions are SVG.
 */
@Component({
  selector: 'app-mascot',
  template: `
    <!-- Recreated on every expression change so the entry animation plays again -->
    @for (s of [src()]; track s) {
      <img class="oso" [src]="s" [alt]="text()" [attr.width]="size()" [attr.height]="size()" decoding="async" />
    }
    @if (showLabel()) {
      <span class="min-w-0">
        <span class="block text-sm font-semibold text-slate-800 dark:text-slate-100">{{ text() }}</span>
        <ng-content />
      </span>
    }
  `,
  host: { class: 'inline-flex items-center gap-3' },
  styleUrl: './mascot.css',
})
export class Mascot {
  private readonly i18n = inject(I18n);
  private readonly reducedMotion = prefersReducedMotion();

  /** Label returned by the model (neutral, contento, sin-resultados…) */
  readonly expression = input<string | null | undefined>();
  /** Text shown next to the bear; defaults to the translated expression name */
  readonly label = input<string | null | undefined>();
  readonly showLabel = input(true);
  readonly size = input(48);

  protected readonly src = computed(() => expressionSrc(this.expression(), this.reducedMotion()));
  protected readonly text = computed(() => this.label() || this.i18n.t(expressionKey(this.expression())));
}
