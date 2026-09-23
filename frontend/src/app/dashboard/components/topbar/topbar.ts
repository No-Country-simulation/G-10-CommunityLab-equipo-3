import { Component, ElementRef, computed, effect, inject, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MenuItem } from 'primeng/api';
import { AvatarModule } from 'primeng/avatar';
import { MenuModule } from 'primeng/menu';
import { TooltipModule } from 'primeng/tooltip';
import { API_CONFIG } from '../../../core/api/community-lab.api';
import { MOCK_STORAGE_KEY } from '../../../core/api/mock-community-lab.api';
import { I18n, LANGS, Lang } from '../../../core/i18n/i18n.service';
import { TranslatePipe } from '../../../core/i18n/translate.pipe';
import { CurrentPage } from '../../../core/current-page';
import { ThemeService } from '../../../core/theme.service';

@Component({
  selector: 'app-topbar',
  imports: [AvatarModule, MenuModule, TooltipModule, TranslatePipe, RouterLink],
  templateUrl: './topbar.html',
  host: {
    '(document:click)': 'closeLangMenuOutside($event)',
    '(document:keydown.escape)': 'langMenuOpen.set(false)',
  },
})
export class Topbar {
  private readonly current = inject(CurrentPage);
  protected readonly theme = inject(ThemeService);
  private readonly api = inject(API_CONFIG);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  protected readonly i18n = inject(I18n);
  protected readonly langs = LANGS;

  readonly toggleSidebar = output<void>();

  protected readonly currentLang = computed(() => LANGS.find((l) => l.code === this.i18n.lang())!);

  constructor() {
    // Browser tab title follows both the page and the language
    effect(() => {
      document.title = `${this.i18n.t(this.current.page().titleKey)} · Kora`;
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

  protected readonly userMenu = computed<MenuItem[]>(() => this.api.useMocks
    ? [
        {
          label: this.i18n.t('topbar.resetDemo'),
          icon: 'pi pi-replay',
          command: () => {
            try {
              localStorage.removeItem(MOCK_STORAGE_KEY);
            } catch {
              // nothing persisted
            }
            location.reload();
          },
        },
      ]
    : [{ label: this.i18n.t('topbar.signOut'), icon: 'pi pi-sign-out' }]);
}
