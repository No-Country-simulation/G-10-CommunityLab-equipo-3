import { Component, computed, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { TooltipModule } from 'primeng/tooltip';
import { ProcessResult, StoredObject } from '../../../core/api/api.models';
import { downloadBlob } from '../../../core/banner';
import { I18n } from '../../../core/i18n/i18n.service';
import { LocalizedDatePipe, TranslatePipe } from '../../../core/i18n/translate.pipe';
import { WorkspaceStore } from '../../../core/workspace.store';

/** Always Free tier: 20 GB across Standard + Infrequent Access + Archive. */
const FREE_TIER_BYTES = 20 * 1024 ** 3;

@Component({
  selector: 'app-storage',
  imports: [ButtonModule, DialogModule, TooltipModule, DecimalPipe, TranslatePipe, LocalizedDatePipe],
  templateUrl: './storage.html',
})
export class Storage {
  protected readonly store = inject(WorkspaceStore);
  private readonly toast = inject(MessageService);
  private readonly i18n = inject(I18n);

  protected readonly bucket = {
    name: 'kora-assets',
    namespace: 'axqlc3m1hy2k',
    region: 'sa-saopaulo-1',
  };

  protected readonly totals = computed(() => {
    const objects = this.store.objects();
    const bytes = objects.reduce((acc, o) => acc + o.sizeBytes, 0);
    return {
      objects: objects.length,
      bytes,
      usagePct: (bytes / FREE_TIER_BYTES) * 100,
    };
  });

  protected readonly viewing = signal<ProcessResult | null>(null);
  protected readonly viewingJson = computed(() => JSON.stringify(this.viewing(), null, 2));
  protected readonly loadingObject = signal<string | null>(null);

  constructor() {
    this.store.refreshStorage();
  }

  open(o: StoredObject) {
    this.loadingObject.set(o.objectName);
    this.store.getStoredObject(o.objectName).subscribe({
      next: (r) => {
        this.viewing.set(r);
        this.loadingObject.set(null);
      },
      error: () => this.loadingObject.set(null),
    });
  }

  download() {
    const r = this.viewing();
    if (r) downloadBlob(new Blob([this.viewingJson()], { type: 'application/json' }), `${r.batchId}.json`);
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
}
