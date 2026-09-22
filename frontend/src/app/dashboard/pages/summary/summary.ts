import { Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { GeneratedAsset } from '../../../core/api/api.models';
import { ASSET_TYPE_ICON, ASSET_TYPE_LABEL, ASSET_TYPE_TINT, ROUTE_LABEL } from '../../../core/ui-maps';
import { WorkspaceStore } from '../../../core/workspace.store';
import { StatCard, StatItem } from '../../../shared/stat-card/stat-card';

@Component({
  selector: 'app-summary',
  imports: [ButtonModule, RouterLink, DatePipe, StatCard],
  templateUrl: './summary.html',
  styleUrl: './summary.css',
})
export class Summary {
  protected readonly store = inject(WorkspaceStore);
  private readonly toast = inject(MessageService);

  protected readonly typeIcon = ASSET_TYPE_ICON;
  protected readonly typeLabel = ASSET_TYPE_LABEL;
  protected readonly typeTint = ASSET_TYPE_TINT;
  protected readonly routeLabel = ROUTE_LABEL;

  /** The four steps the challenge asks for, each linked to where it happens in the app. */
  protected readonly steps = [
    { title: 'Ingesta', text: 'Carga mensajes de la comunidad en JSON, CSV o escríbelos.', icon: 'pi pi-inbox', link: '/ingest' },
    { title: 'Análisis con LLM', text: 'Sentimiento, temas y relevancia de cada mensaje.', icon: 'pi pi-sparkles', link: '/ingest' },
    { title: 'Orquestación', text: 'Logro → LinkedIn + Newsletter · Duda → FAQ. Tú apruebas.', icon: 'pi pi-sitemap', link: '/content' },
    { title: 'OCI Object Storage', text: 'El paquete JSON queda guardado en el bucket.', icon: 'pi pi-cloud-upload', link: '/storage' },
  ];

  protected readonly statColors = ['#6366f1', '#14b8a6', '#f59e0b', '#c74634'];

  protected readonly stats = computed<StatItem[]>(() => {
    const objects = this.store.objects();
    return [
      { label: 'Interacciones analizadas', value: objects.reduce((acc, o) => acc + o.interactions, 0), icon: 'pi pi-comments', link: '/ingest' },
      { label: 'Contenidos generados', value: this.store.assets().length, icon: 'pi pi-file-edit', link: '/content' },
      { label: 'Por revisar', value: this.store.pendingReview(), icon: 'pi pi-eye', link: '/content' },
      { label: 'Paquetes en OCI', value: objects.length, icon: 'pi pi-cloud', link: '/storage' },
    ];
  });

  protected readonly pending = computed(() =>
    this.store
      .assets()
      .filter((a) => a.status === 'draft' || a.status === 'in_review')
      .slice(0, 5),
  );

  protected readonly latestObjects = computed(() => this.store.objects().slice(0, 4));

  /** Items animating out of the pending list before the store update removes them. */
  protected readonly leaving = signal<ReadonlySet<string>>(new Set());

  approve(a: GeneratedAsset) {
    this.leaving.update((s) => new Set(s).add(a.id));
    setTimeout(() => {
      this.store.update(a.id, { status: 'approved' });
      this.leaving.update((s) => {
        const next = new Set(s);
        next.delete(a.id);
        return next;
      });
    }, 280);
    this.toast.add({ severity: 'success', summary: 'Aprobado', detail: a.title, life: 2500 });
  }

  fileName(objectName: string) {
    return objectName.split('/').pop();
  }
}
