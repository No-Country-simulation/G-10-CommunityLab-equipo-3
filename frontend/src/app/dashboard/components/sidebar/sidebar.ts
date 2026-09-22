import { Component, inject, input, output } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink } from '@angular/router';
import { filter, map, startWith } from 'rxjs';
import { TooltipModule } from 'primeng/tooltip';
import { API_CONFIG } from '../../../core/api/community-lab.api';
import { WorkspaceStore } from '../../../core/workspace.store';

interface NavItem {
  label: string;
  icon: string;
  path: string;
  hint: string;
  badge?: () => number;
}

@Component({
  selector: 'app-sidebar',
  imports: [RouterLink, TooltipModule],
  templateUrl: './sidebar.html',
})
export class Sidebar {
  private readonly store = inject(WorkspaceStore);
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
    { label: 'Resumen', icon: 'pi pi-th-large', path: '/', hint: 'Estado del motor' },
    { label: 'Ingesta y análisis', icon: 'pi pi-inbox', path: '/ingest', hint: 'Mensajes → IA → contenido' },
    { label: 'Curaduría', icon: 'pi pi-file-edit', path: '/content', hint: 'Editar, aprobar y publicar', badge: this.store.pendingReview },
    { label: 'OCI Object Storage', icon: 'pi pi-cloud', path: '/storage', hint: 'Paquetes guardados' },
  ];

  /** Item height (52px) + gap (space-y-1 = 4px): distance the active pill moves per item. */
  protected readonly itemStep = 56;

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
