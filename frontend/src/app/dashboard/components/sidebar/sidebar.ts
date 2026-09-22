import { Component, inject, input, output } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
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
  imports: [RouterLink, RouterLinkActive, TooltipModule],
  templateUrl: './sidebar.html',
})
export class Sidebar {
  private readonly store = inject(WorkspaceStore);
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
}
