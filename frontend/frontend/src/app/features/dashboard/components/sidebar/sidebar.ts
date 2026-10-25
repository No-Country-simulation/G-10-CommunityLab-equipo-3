import { Component, computed, inject, input, output } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink } from '@angular/router';
import { filter, map, startWith } from 'rxjs';
import { MessageService } from 'primeng/api';
import { TooltipModule } from 'primeng/tooltip';
import { INTERACTION_SOURCES } from '../../../../core/api/api.models';
import { API_CONFIG } from '../../../../core/api/community-lab.api';
import { I18n } from '../../../../core/i18n/i18n.service';
import { TranslatePipe } from '../../../../core/i18n/translate.pipe';
import { SOURCE_BRAND, SOURCE_ICON, SOURCE_TINT } from '../../../../core/ui-maps';
import { SENTIMENT_EXPRESSION } from '../../../../core/mascot';
import { PAGE_ICON } from '../../../../core/page-icons';
import { NetworkFilter, WorkspaceStore } from '../../../../core/workspace.store';
import { Mascot } from '../../../../shared/mascot/mascot';

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
  imports: [RouterLink, TooltipModule, TranslatePipe, Mascot],
  templateUrl: './sidebar.html',
})
export class Sidebar {
  protected readonly store = inject(WorkspaceStore);
  private readonly toast = inject(MessageService);
  private readonly i18n = inject(I18n);
  private readonly router = inject(Router);
  protected readonly api = inject(API_CONFIG);

  /** Status card badge per mode: demo, or the live stream state of the selected network */
  protected readonly sourceIcon = SOURCE_ICON;
  protected readonly sourceTint = SOURCE_TINT;
  protected readonly modeBadge: Record<string, string> = {
    demo: 'border-amber-500/20 bg-amber-50 text-amber-700 dark:bg-amber-500/10 dark:text-amber-300',
    open: 'border-emerald-500/20 bg-emerald-50 text-emerald-700 dark:bg-emerald-500/10 dark:text-emerald-300',
    connecting: 'border-amber-500/20 bg-amber-50 text-amber-700 dark:bg-amber-500/10 dark:text-amber-300',
    error: 'border-rose-500/20 bg-rose-50 text-rose-700 dark:bg-rose-500/10 dark:text-rose-300',
    off: 'border-stone-300/60 bg-stone-100 text-stone-500 dark:bg-stone-800 dark:text-stone-400',
  };
  /** Badge text: says the state too, so a red badge never reads "LIVE" */
  protected readonly modeLabel: Record<string, string> = {
    demo: 'MOCK',
    open: 'LIVE',
    connecting: '…',
    error: 'OFF',
    off: 'OFF',
  };
  protected readonly modeDot: Record<string, string> = {
    demo: 'bg-amber-500',
    open: 'bg-emerald-500',
    connecting: 'bg-amber-500',
    error: 'bg-rose-500',
    off: 'bg-stone-400',
  };

  /** Mobile drawer visibility */
  readonly open = input(false);
  /** Desktop icon-only mode */
  readonly collapsed = input(false);
  readonly close = output<void>();
  readonly toggleCollapse = output<void>();

  /** Curate → store in OCI; ingestion and analysis happen in the backend. */
  protected readonly items: NavItem[] = [
    { label: 'nav.summary', path: '/app', emoji: PAGE_ICON.summary },
    { label: 'nav.content', path: '/app/content', emoji: PAGE_ICON.content, badge: this.store.pendingReview },
    { label: 'nav.storage', path: '/app/storage', emoji: PAGE_ICON.storage },
  ];

  /**
   * "All" first, then every network, Discord-server-list style: one click selects it, each shows what is
   * waiting for review ("All" adds up both networks).
   */
  protected readonly networks = computed(() => {
    const active = this.store.activeSource();
    const pending = this.store.pendingBySource();
    const status = (n: number) => (n ? this.i18n.t('sidebar.network.pending', { n }) : this.i18n.t('sidebar.network.clear'));
    const total = INTERACTION_SOURCES.reduce((acc, s) => acc + pending[s], 0);
    return [
      {
        source: 'all' as NetworkFilter,
        name: this.i18n.t('sidebar.network.all'),
        active: active === 'all',
        icon: 'pi pi-th-large',
        gradient: 'linear-gradient(135deg, #f59e0b 0%, #b45309 100%)',
        pending: total,
        status: status(total),
      },
      ...INTERACTION_SOURCES.map((source) => {
        const [from, to] = SOURCE_BRAND[source];
        return {
          source: source as NetworkFilter,
          name: source,
          active: source === active,
          icon: SOURCE_ICON[source],
          gradient: `linear-gradient(135deg, ${from} 0%, ${to} 100%)`,
          pending: pending[source],
          status: status(pending[source]),
        };
      }),
    ];
  });

  protected selectNetwork(source: NetworkFilter) {
    if (source === this.store.activeSource()) return;
    this.store.setActiveSource(source);
    this.toast.add({
      severity: 'info',
      summary: this.i18n.t('sidebar.network.toast'),
      detail: this.i18n.t('sidebar.network.toastDetail', { name: this.store.scopeLabel() }),
      life: 3000,
    });
  }

  /** Community mood: the animated bear plus its texts, or the neutral bear while no message has arrived. */
  protected readonly mood = computed(() => {
    const m = this.store.communityMood();
    const t = (key: string, params?: Record<string, string | number>) => this.i18n.t(key, params);
    if (!m) return { expression: SENTIMENT_EXPRESSION.neutral, value: t('sidebar.mood.empty'), detail: '', counts: null };
    return {
      expression: SENTIMENT_EXPRESSION[m.mood],
      counts: m.counts,
      value: t('sidebar.mood.value', { mood: t('mood.' + m.mood).toLowerCase() }),
      detail: t('sidebar.mood.counts', { p: m.counts.positive, n: m.counts.neutral, neg: m.counts.negative }),
    };
  });

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
    return this.items.findIndex((it) => (it.path === '/app' ? path === '/app' : path.startsWith(it.path)));
  }
}
