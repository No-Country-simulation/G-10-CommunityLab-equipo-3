import { Component, computed, inject, input, output } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink } from '@angular/router';
import { filter, map, startWith } from 'rxjs';
import { MessageService } from 'primeng/api';
import { TooltipModule } from 'primeng/tooltip';
import { API_CONFIG } from '../../../../core/api/community-lab.api';
import { I18n } from '../../../../core/i18n/i18n.service';
import { TranslatePipe } from '../../../../core/i18n/translate.pipe';
import { SOURCE_BRAND, SOURCE_ICON } from '../../../../core/ui-maps';
import { PAGE_ICON } from '../../../../core/page-icons';
import { WorkspaceStore } from '../../../../core/workspace.store';

interface NavItem {
  /** i18n key */
  label: string;
  path: string;
  /** 3D icon (Microsoft Fluent Emoji, MIT) */
  emoji: string;
  badge?: () => number;
}

@Component({
  selector: 'app-sidebar',
  imports: [RouterLink, TooltipModule, TranslatePipe],
  templateUrl: './sidebar.html',
})
export class Sidebar {
  protected readonly store = inject(WorkspaceStore);
  private readonly toast = inject(MessageService);
  private readonly i18n = inject(I18n);
  private readonly router = inject(Router);
  protected readonly api = inject(API_CONFIG);

  /** Mobile drawer visibility */
  readonly open = input(false);
  /** Desktop icon-only mode */
  readonly collapsed = input(false);
  readonly close = output<void>();
  readonly toggleCollapse = output<void>();

  /** Mirrors the challenge flow: ingest + analyze → curate → store in OCI. */
  protected readonly items: NavItem[] = [
    { label: 'nav.summary', path: '/', emoji: PAGE_ICON.summary },
    { label: 'nav.ingest', path: '/ingest', emoji: PAGE_ICON.ingest },
    { label: 'nav.content', path: '/content', emoji: PAGE_ICON.content, badge: this.store.pendingReview },
    { label: 'nav.storage', path: '/storage', emoji: PAGE_ICON.storage },
  ];

  /** Same behavior as the CommuPulse server card: each click flips Discord ↔ Telegram. */
  protected readonly activeNetwork = computed(() => {
    const source = this.store.activeSource();
    const count = this.store.sourceCounts()[source];
    const [from, to] = SOURCE_BRAND[source];
    return {
      source,
      name: `${source}: CommunityLab`,
      icon: SOURCE_ICON[source],
      gradient: `linear-gradient(135deg, ${from} 0%, ${to} 100%)`,
      detail: this.i18n.t(count === 1 ? 'common.contentCount.one' : 'common.contentCount.other', { n: count }),
    };
  });

  protected toggleNetwork() {
    const next = this.store.activeSource() === 'Discord' ? 'Telegram' : 'Discord';
    this.store.setActiveSource(next);
    this.toast.add({
      severity: 'info',
      summary: this.i18n.t('sidebar.network.toast'),
      detail: this.i18n.t('sidebar.network.toastDetail', { name: `${next}: CommunityLab` }),
      life: 3000,
    });
  }

  /** Item height (46px) + gap (space-y-2 = 8px): distance the active pill moves per item. */
  protected readonly itemStep = 54;

  protected readonly activeIndex = toSignal(
    this.router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      startWith(null),
      map(() => this.indexFor(this.router.url)),
    ),
    { initialValue: this.indexFor(this.router.url) },
  );

  private indexFor(url: string): number {
    const path = url.split(/[?#]/)[0] || '/';
    return this.items.findIndex((it) => (it.path === '/' ? path === '/' : path.startsWith(it.path)));
  }
}
