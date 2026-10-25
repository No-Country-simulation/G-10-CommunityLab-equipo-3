import { Component, computed, inject, signal } from '@angular/core';
import { MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { TooltipModule } from 'primeng/tooltip';
import { StoredObject } from '../../../../core/api/api.models';
import { StoredObjectView } from '../../../../core/api/community-lab.api';
import { downloadBlob } from '../../../../core/banner';
import { I18n } from '../../../../core/i18n/i18n.service';
import { LocalizedDatePipe, LocalizedNumberPipe, TranslatePipe } from '../../../../core/i18n/translate.pipe';
import { WorkspaceStore } from '../../../../core/workspace.store';

/** Bucket limit when the backend stats are not available */
const FALLBACK_LIMIT_BYTES = 10 * 1024 ** 3;

/** Request counters shown one by one, in the order of the stats contract */
const REQUEST_KINDS = [
  { key: 'get', method: 'GET', icon: 'pi pi-download' },
  { key: 'put', method: 'PUT', icon: 'pi pi-upload' },
  { key: 'list', method: 'LIST', icon: 'pi pi-list' },
  { key: 'head', method: 'HEAD', icon: 'pi pi-info-circle' },
  { key: 'delete', method: 'DELETE', icon: 'pi pi-trash' },
  { key: 'clientErrors', method: '4xx', icon: 'pi pi-exclamation-triangle' },
] as const;

@Component({
  selector: 'app-storage',
  imports: [ButtonModule, DialogModule, TooltipModule, TranslatePipe, LocalizedDatePipe, LocalizedNumberPipe],
  templateUrl: './storage.html',
})
export class Storage {
  protected readonly store = inject(WorkspaceStore);
  private readonly toast = inject(MessageService);
  private readonly i18n = inject(I18n);

  /** Bucket the backend writes the packages to (backend spec 006) */
  protected readonly bucket = {
    name: 'MessagesUsers',
    namespace: 'gr850hk0ujdi',
    region: 'sa-saopaulo-1',
  };

  /** Bucket usage: measured by the backend, else estimated from the objects this browser lists */
  protected readonly totals = computed(() => {
    const stats = this.store.storageStats()?.storage;
    const objects = this.store.objects();
    const bytes = stats?.usedBytes ?? objects.reduce((acc, o) => acc + o.sizeBytes, 0);
    const limit = stats?.limitBytes ?? FALLBACK_LIMIT_BYTES;
    return {
      objects: stats?.objectCount ?? objects.length,
      bytes,
      limit,
      usagePct: pct(bytes, limit),
    };
  });

  protected readonly requests = computed(() => {
    const r = this.store.storageStats()?.requests;
    if (!r) return null;
    return {
      ...r,
      usagePct: pct(r.total, r.limit),
      kinds: REQUEST_KINDS.map((k) => ({ ...k, count: r[k.key], share: pct(r[k.key], r.total) })),
    };
  });

  protected readonly viewing = signal<StoredObjectView | null>(null);
  protected readonly viewingJson = computed(() => JSON.stringify(this.viewing()?.content, null, 2));
  protected readonly loadingObject = signal<string | null>(null);

  constructor() {
    this.store.refreshStorage();
  }

  open(o: StoredObject) {
    this.loadingObject.set(o.objectName);
    this.store.getStoredObject(o.objectName).subscribe({
      next: (r) => {
        this.viewing.set(r as StoredObjectView);
        this.loadingObject.set(null);
      },
      error: () => this.loadingObject.set(null),
    });
  }

  download() {
    const r = this.viewing();
    // Same file name as in the bucket (e.g. paquete-<batchId>.json)
    if (r) downloadBlob(new Blob([this.viewingJson()], { type: 'application/json' }), r.objectName.split('/').pop()!);
  }

  copy(text: string) {
    navigator.clipboard?.writeText(text).then(() =>
      this.toast.add({ severity: 'info', summary: this.i18n.t('toast.copied.url'), life: 2000 }),
    );
  }

  isNew(o: StoredObject) {
    return Date.now() - new Date(o.storedAt).getTime() < 10 * 60 * 1000;
  }

  fileName(objectName: string) {
    return objectName.split('/').pop();
  }

  folder(objectName: string) {
    return objectName.split('/').slice(0, -1).join('/') + '/';
  }

  formatBytes(bytes: number) {
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let i = 0;
    while (bytes >= 1024 && i < units.length - 1) {
      bytes /= 1024;
      i++;
    }
    const digits = i === 0 || bytes >= 100 ? 0 : 1;
    return `${new Intl.NumberFormat(this.i18n.locale(), { maximumFractionDigits: digits }).format(bytes)} ${units[i]}`;
  }

  /** 0.6 → "0,6 %" (es) / "0.6%" (en) */
  percent(pct: number) {
    return new Intl.NumberFormat(this.i18n.locale(), { style: 'percent', maximumFractionDigits: 1 }).format(pct / 100);
  }

  /** Bar color by how close it is to the limit */
  barColor(usagePct: number) {
    return usagePct >= 90 ? 'bg-rose-500' : usagePct >= 70 ? 'bg-amber-500' : 'bg-emerald-500';
  }
}

const pct = (value: number, total: number) => (total > 0 ? (value / total) * 100 : 0);
