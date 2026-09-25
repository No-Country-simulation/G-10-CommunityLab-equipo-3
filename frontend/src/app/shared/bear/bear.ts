import { Component, computed, input, signal } from '@angular/core';

/**
 * Poses of Kora, the spectacled bear, in public/image/bear/:
 *   laptop     → oso-laptop      working on a laptop          (landing hero)
 *   mensajes   → oso-mensajes    reading messages on a tablet (landing "Cómo funciona")
 *   curaduria  → oso-curaduria   thumbs up at a desk          (landing "Plataforma")
 *   saludo     → oso-saludo      waving                       (landing CTA, dashboard welcome)
 * Each pose is looked up as an optimized .webp first, then a .png.
 * Full-resolution PNG originals live in frontend/design/bear/ (not published); export a .webp from them.
 * While neither exists, the sitting bear is shown with a per-pose variation instead.
 */
export type BearPose = 'laptop' | 'mensajes' | 'curaduria' | 'saludo';

const FALLBACK = 'image/logo/oso-full.png';

/** How the fallback differs per pose, so the page never repeats the exact same bear. */
const FALLBACK_STYLE: Record<BearPose, string> = {
  laptop: '',
  mensajes: '-scale-x-100',
  curaduria: '-scale-x-100 -rotate-3',
  saludo: 'rotate-3',
};

/** Sources tried in order: optimized webp, original png, fallback */
type Stage = 0 | 1 | 2;

@Component({
  selector: 'app-bear',
  template: `
    <img [src]="src()" [alt]="alt()" [attr.width]="width()" [attr.height]="height()"
      [attr.loading]="eager() ? 'eager' : 'lazy'" decoding="async"
      [class]="imgClass() + ' ' + (stage() === 2 ? fallbackStyle() : '')" (error)="onError()" />
  `,
  host: { class: 'contents' },
})
export class Bear {
  readonly pose = input.required<BearPose>();
  readonly alt = input('');
  readonly imgClass = input('');
  /** Image shown until the pose file exists */
  readonly fallback = input(FALLBACK);
  readonly width = input(300);
  readonly height = input(360);
  /** Above-the-fold images load right away */
  readonly eager = input(false);

  protected readonly stage = signal<Stage>(0);
  protected readonly src = computed(() => {
    const base = `image/bear/oso-${this.pose()}`;
    return [`${base}.webp`, `${base}.png`, this.fallback()][this.stage()];
  });
  protected readonly fallbackStyle = computed(() => FALLBACK_STYLE[this.pose()]);

  protected onError() {
    // Step through the sources once; if the fallback itself fails, stop retrying
    const s = this.stage();
    if (s < 2) this.stage.set((s + 1) as Stage);
  }
}
