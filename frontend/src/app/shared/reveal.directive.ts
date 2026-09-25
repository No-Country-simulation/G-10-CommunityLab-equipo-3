import { Directive, ElementRef, OnDestroy, OnInit, inject, input, numberAttribute } from '@angular/core';

/**
 * Fades an element up the first time it scrolls into view.
 * Without IntersectionObserver (or with reduced motion) the element is simply shown.
 */
@Directive({
  selector: '[appReveal]',
  host: { class: 'reveal' },
})
export class Reveal implements OnInit, OnDestroy {
  /** Delay in ms, to stagger siblings (`appReveal="120"` or `[appReveal]="i * 80"`) */
  readonly appReveal = input(0, { transform: (v: unknown) => numberAttribute(v, 0) });

  private readonly el = inject<ElementRef<HTMLElement>>(ElementRef);
  private observer?: IntersectionObserver;

  ngOnInit() {
    const node = this.el.nativeElement;
    const delay = this.appReveal();
    if (delay) node.style.transitionDelay = `${delay}ms`;

    // Hidden tabs (opened in background, capture tools) get no intersection callbacks: never leave content invisible
    if (typeof IntersectionObserver === 'undefined' || document.visibilityState === 'hidden') {
      node.classList.add('is-visible');
      return;
    }
    this.observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) {
          node.classList.add('is-visible');
          this.observer?.disconnect();
        }
      },
      { threshold: 0.12, rootMargin: '0px 0px -40px 0px' },
    );
    this.observer.observe(node);
  }

  ngOnDestroy() {
    this.observer?.disconnect();
  }
}
