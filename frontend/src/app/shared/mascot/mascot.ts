import { Component, ViewEncapsulation, computed, effect, inject, input, signal } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { I18n } from '../../core/i18n/i18n.service';
import { DEFAULT_EXPRESSION, expressionKey, expressionSrc, toExpression } from '../../core/mascot';

/** Each SVG is downloaded once and reused by every bear on the page */
const svgCache = new Map<string, Promise<string>>();

function loadSvg(src: string): Promise<string> {
  let svg = svgCache.get(src);
  if (!svg) {
    svg = fetch(src).then((r) => (r.ok ? r.text() : Promise.reject(r.status)));
    svgCache.set(src, svg);
    svg.catch(() => svgCache.delete(src));
  }
  return svg;
}

/**
 * The mascot showing the expression the LLM picked, next to a short text.
 *   <app-mascot [expression]="a.origin.expression" />                → bear + "Celebrando" (in the UI language)
 *   <app-mascot [expression]="x" [label]="summary.overallSentiment" /> → bear + custom text
 *   <app-mascot [expression]="x" [showLabel]="false" [size]="96" />   → bear only
 * Unknown or missing labels fall back to the neutral bear.
 * The SVG is inlined (not an <img>) so mascot.css can animate its named parts: .ojo, .boca, .rubor, .lagrima.
 */
@Component({
  selector: 'app-mascot',
  template: `
    <!-- Recreated on every expression change so the entry animation plays again -->
    @for (e of [expression()]; track e) {
      @if (svg(); as markup) {
        <span class="oso" [class]="mood()" [style.width.px]="size()" [style.height.px]="size()"
          role="img" [attr.aria-label]="text()" [innerHTML]="markup"></span>
      } @else {
        <img class="oso" [src]="src()" [alt]="text()" [attr.width]="size()" [attr.height]="size()" decoding="async" />
      }
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
  // The inlined SVG is not part of this template, so the styles must not be scoped
  encapsulation: ViewEncapsulation.None,
})
export class Mascot {
  private readonly i18n = inject(I18n);
  private readonly sanitizer = inject(DomSanitizer);

  /** Label returned by the model (neutral, contento, sin-resultados…) */
  readonly expression = input<string | null | undefined>();
  /** Text shown next to the bear; defaults to the translated expression name */
  readonly label = input<string | null | undefined>();
  readonly showLabel = input(true);
  readonly size = input(48);

  protected readonly src = computed(() => expressionSrc(this.expression()));
  protected readonly text = computed(() => this.label() || this.i18n.t(expressionKey(this.expression())));
  /** Class used by mascot.css: "oso contento", "oso triste"… */
  protected readonly mood = computed(() => `oso ${toExpression(this.expression()) ?? DEFAULT_EXPRESSION}`);

  /** Inline markup of the current SVG; null while loading (or if it fails) → the <img> is shown instead */
  protected readonly svg = signal<SafeHtml | null>(null);

  constructor() {
    effect((onCleanup) => {
      const src = this.src();
      let current = true;
      onCleanup(() => (current = false));
      this.svg.set(null);
      loadSvg(src).then(
        // Our own static asset: trusted so Angular keeps the <svg> markup
        (markup) => current && this.svg.set(this.sanitizer.bypassSecurityTrustHtml(markup)),
        () => {},
      );
    });
  }
}
