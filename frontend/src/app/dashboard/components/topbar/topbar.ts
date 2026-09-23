import { Component, computed, effect, inject, output } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { filter, map, startWith } from 'rxjs';
import { MenuItem } from 'primeng/api';
import { AvatarModule } from 'primeng/avatar';
import { MenuModule } from 'primeng/menu';
import { TooltipModule } from 'primeng/tooltip';
import { API_CONFIG } from '../../../core/api/community-lab.api';
import { MOCK_STORAGE_KEY } from '../../../core/api/mock-community-lab.api';
import { I18n, LANGS, Lang } from '../../../core/i18n/i18n.service';
import { TranslatePipe } from '../../../core/i18n/translate.pipe';
import { PAGE_ICON } from '../../../core/page-icons';
import { ThemeService } from '../../../core/theme.service';

@Component({
  selector: 'app-topbar',
  imports: [AvatarModule, MenuModule, TooltipModule, TranslatePipe],
  templateUrl: './topbar.html',
})
export class Topbar {
  private readonly router = inject(Router);
  protected readonly theme = inject(ThemeService);
  private readonly api = inject(API_CONFIG);
  protected readonly i18n = inject(I18n);
  protected readonly langs = LANGS;

  readonly toggleSidebar = output<void>();

  protected readonly page = toSignal(
    this.router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      startWith(null),
      map(() => {
        let r = this.router.routerState.snapshot.root;
        while (r.firstChild) r = r.firstChild;
        return {
          titleKey: (r.data['titleKey'] as string | undefined) ?? 'nav.summary',
          icon: (r.data['icon'] as string | undefined) ?? PAGE_ICON.summary,
        };
      }),
    ),
    { initialValue: { titleKey: 'nav.summary', icon: PAGE_ICON.summary } },
  );

  protected readonly currentLang = computed(() => LANGS.find((l) => l.code === this.i18n.lang())!);

  constructor() {
    // Browser tab title follows both the page and the language
    effect(() => {
      document.title = `${this.i18n.t(this.page().titleKey)} · Kora`;
    });
  }

  protected setLang(lang: Lang) {
    this.i18n.set(lang);
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
