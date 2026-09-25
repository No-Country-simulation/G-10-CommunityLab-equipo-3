import { AfterViewInit, Component, DestroyRef, ElementRef, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { I18n, LANGS, Lang } from '../../core/i18n/i18n.service';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { ThemeService } from '../../core/theme.service';
import { Bear } from '../../shared/bear/bear';
import { Reveal } from '../../shared/reveal.directive';

interface Section {
  id: string;
  key: string;
}

/** Public landing page: presents Kora and leads into the dashboard (/app). */
@Component({
  selector: 'app-landing',
  imports: [RouterLink, TranslatePipe, Reveal, Bear],
  templateUrl: './landing.html',
  host: {
    '(window:scroll)': 'onScroll()',
    '(document:keydown.escape)': 'menuOpen.set(false)',
  },
})
export class Landing implements AfterViewInit {
  protected readonly i18n = inject(I18n);
  protected readonly theme = inject(ThemeService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly langs = LANGS;
  protected readonly menuOpen = signal(false);
  protected readonly scrolled = signal(false);
  protected readonly active = signal('inicio');

  protected readonly sections: Section[] = [
    { id: 'inicio', key: 'landing.nav.home' },
    { id: 'funciones', key: 'landing.nav.features' },
    { id: 'como-funciona', key: 'landing.nav.how' },
    { id: 'ejemplo', key: 'landing.nav.example' },
    { id: 'plataforma', key: 'landing.nav.platform' },
  ];

  /** Same four steps and colors as "Cómo funciona" in the dashboard summary. */
  protected readonly features = [
    { icon: 'pi pi-inbox', title: 'landing.features.ingest.title', text: 'landing.features.ingest.text', link: '/app/ingest', color: '#d97706' },
    { icon: 'pi pi-sparkles', title: 'landing.features.analysis.title', text: 'landing.features.analysis.text', link: '/app/ingest', color: '#8b5cf6' },
    { icon: 'pi pi-sitemap', title: 'landing.features.content.title', text: 'landing.features.content.text', link: '/app/content', color: '#10b981' },
    { icon: 'pi pi-cloud-upload', title: 'landing.features.storage.title', text: 'landing.features.storage.text', link: '/app/storage', color: '#c74634' },
  ];

  protected readonly steps = [
    { title: 'landing.how.s1.title', text: 'landing.how.s1.text' },
    { title: 'landing.how.s2.title', text: 'landing.how.s2.text' },
    { title: 'landing.how.s3.title', text: 'landing.how.s3.text' },
    { title: 'landing.how.s4.title', text: 'landing.how.s4.text' },
  ];

  /** Facts about the product, not marketing figures. */
  protected readonly stats = [
    { value: '4', key: 'landing.platform.stat.formats' },
    { value: '2', key: 'landing.platform.stat.networks' },
    { value: '3', key: 'landing.platform.stat.langs' },
    { value: '$0', key: 'landing.platform.stat.cost' },
  ];

  /** The brief's example: what goes out after processing Mariana's and Lucas's messages. */
  protected readonly outputs = [
    { icon: 'pi pi-linkedin', key: 'landing.example.linkedin', tint: 'bg-[#0a66c2]/10 text-[#0a66c2] dark:bg-[#0a66c2]/20 dark:text-[#70b5f9]' },
    { icon: 'pi pi-envelope', key: 'landing.example.newsletter', tint: 'bg-amber-50 text-amber-700 dark:bg-amber-500/10 dark:text-amber-300' },
    { icon: 'pi pi-question-circle', key: 'landing.example.faq', tint: 'bg-violet-50 text-violet-700 dark:bg-violet-500/10 dark:text-violet-300' },
    { icon: 'pi pi-cloud', key: 'landing.example.oci', tint: 'bg-orange-50 text-[#c74634] dark:bg-orange-500/10 dark:text-orange-300' },
  ];

  constructor() {
    effect(() => {
      document.title = `Kora AI · ${this.i18n.t('brand.tagline')}`;
    });
  }

  ngAfterViewInit() {
    if (typeof IntersectionObserver === 'undefined') return;
    // Highlight the nav link of the section in the middle of the viewport
    const observer = new IntersectionObserver(
      (entries) => {
        const visible = entries.find((e) => e.isIntersecting);
        if (visible) this.active.set(visible.target.id);
      },
      { rootMargin: '-45% 0px -50% 0px' },
    );
    this.sections.forEach((s) => {
      const el = this.host.nativeElement.querySelector(`#${s.id}`);
      if (el) observer.observe(el);
    });
    this.destroyRef.onDestroy(() => observer.disconnect());
  }

  protected scrollTo(id: string, event?: Event) {
    event?.preventDefault();
    this.menuOpen.set(false);
    const el = document.getElementById(id);
    if (!el) return;
    const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
    const top = id === 'inicio' ? 0 : el.getBoundingClientRect().top + scrollY - 96;
    window.scrollTo({ top, behavior: reduced ? 'auto' : 'smooth' });
  }

  protected onScroll() {
    this.scrolled.set(window.scrollY > 8);
  }

  protected setLang(lang: Lang) {
    this.i18n.set(lang);
  }
}
