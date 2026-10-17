import { Component, ElementRef, computed, effect, inject, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TooltipModule } from 'primeng/tooltip';
import { I18n, LANGS, Lang } from '../../../../core/i18n/i18n.service';
import { TranslatePipe } from '../../../../core/i18n/translate.pipe';
import { CurrentPage } from '../../../../core/current-page';
import { ThemeService } from '../../../../core/theme.service';
import { Breadcrumb } from '../breadcrumb/breadcrumb';

@Component({
  selector: 'app-topbar',
  imports: [TooltipModule, TranslatePipe, RouterLink, Breadcrumb],
  templateUrl: './topbar.html',
  host: {
    // No box of its own: the sticky shell must be a child of the tall page column to stay pinned
    class: 'contents',
    '(document:click)': 'closeLangMenuOutside($event)',
    '(document:keydown.escape)': 'langMenuOpen.set(false)',
  },
})
export class Topbar {
  private readonly current = inject(CurrentPage);
  protected readonly theme = inject(ThemeService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  protected readonly i18n = inject(I18n);
  protected readonly langs = LANGS;

  readonly toggleSidebar = output<void>();

  protected readonly currentLang = computed(() => LANGS.find((l) => l.code === this.i18n.lang())!);

  constructor() {
    // Browser tab title follows both the page and the language
    effect(() => {
      document.title = `${this.i18n.t(this.current.page().titleKey)} · Ukuku AI`;
    });
  }

  /** Phone-size language dropdown */
  protected readonly langMenuOpen = signal(false);

  protected setLang(lang: Lang) {
    this.i18n.set(lang);
    this.langMenuOpen.set(false);
  }

  protected closeLangMenuOutside(event: MouseEvent) {
    const dropdown = this.host.nativeElement.querySelector('.lang-dropdown');
    if (this.langMenuOpen() && dropdown && !dropdown.contains(event.target as Node)) {
      this.langMenuOpen.set(false);
    }
  }
}
