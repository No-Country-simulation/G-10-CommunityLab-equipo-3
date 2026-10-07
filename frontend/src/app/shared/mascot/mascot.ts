import { Component, computed, input } from '@angular/core';
import { expressionLabel, expressionSrc } from '../../core/mascot';

/**
 * The mascot showing the expression the LLM picked, next to a short text.
 *   <app-mascot [expression]="a.origin.expression" />                → bear + "Celebrando"
 *   <app-mascot [expression]="x" [label]="summary.overallSentiment" /> → bear + custom text
 *   <app-mascot [expression]="x" [showLabel]="false" [size]="96" />   → bear only
 * Unknown or missing labels fall back to the neutral bear.
 */
@Component({
  selector: 'app-mascot',
  template: `
    <img [src]="src()" [alt]="text()" [attr.width]="size()" [attr.height]="size()" decoding="async"
      class="shrink-0 rounded-full" />
    @if (showLabel()) {
      <span class="min-w-0">
        <span class="block text-sm font-semibold text-slate-800 dark:text-slate-100">{{ text() }}</span>
        <ng-content />
      </span>
    }
  `,
  host: { class: 'inline-flex items-center gap-3' },
  styles: `
    img { animation: mascot-pop 0.35s ease-out; }
    @media (prefers-reduced-motion: reduce) { img { animation: none; } }
    @keyframes mascot-pop {
      from { transform: scale(0.85); opacity: 0; }
      to { transform: scale(1); opacity: 1; }
    }
  `,
})
export class Mascot {
  /** Label returned by the model (neutral, contento, sin-resultados…) */
  readonly expression = input<string | null | undefined>();
  /** Text shown next to the bear; defaults to the expression name */
  readonly label = input<string | null | undefined>();
  readonly showLabel = input(true);
  readonly size = input(48);

  protected readonly src = computed(() => expressionSrc(this.expression()));
  protected readonly text = computed(() => this.label() || expressionLabel(this.expression()));
}
