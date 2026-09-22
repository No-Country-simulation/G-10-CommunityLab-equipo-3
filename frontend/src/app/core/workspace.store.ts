import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import {
  AssetPatch,
  AssetStatus,
  GeneratedAsset,
  Interaction,
  ProcessResult,
  StoredObject,
} from './api/api.models';
import { CommunityLabApi } from './api/community-lab.api';

/** Shared state for ingestion results, curation and OCI storage, backed by CommunityLabApi. */
@Injectable({ providedIn: 'root' })
export class WorkspaceStore {
  private readonly api = inject(CommunityLabApi);

  private readonly _assets = signal<GeneratedAsset[]>([]);
  private readonly _objects = signal<StoredObject[]>([]);
  private readonly _lastResult = signal<ProcessResult | null>(null);
  private readonly _busyIds = signal<Set<string>>(new Set());

  readonly assets = this._assets.asReadonly();
  readonly objects = this._objects.asReadonly();
  readonly lastResult = this._lastResult.asReadonly();
  readonly loaded = signal(false);

  readonly pipeline = computed(() => {
    const order: AssetStatus[] = ['draft', 'in_review', 'approved', 'published'];
    const list = this._assets();
    return order.map((status) => ({ status, count: list.filter((a) => a.status === status).length }));
  });

  readonly pendingReview = computed(
    () => this._assets().filter((a) => a.status === 'draft' || a.status === 'in_review').length,
  );

  constructor() {
    this.api.listAssets().subscribe((assets) => {
      this._assets.set(assets);
      this.loaded.set(true);
    });
    this.refreshStorage();
  }

  isBusy(id: string) {
    return this._busyIds().has(id);
  }

  process(interactions: Interaction[]): Observable<ProcessResult> {
    return this.api.processInteractions({ interactions }).pipe(
      tap((result) => {
        this._lastResult.set(result);
        this._assets.update((list) => [...result.assets, ...list]);
        this.refreshStorage();
      }),
    );
  }

  clearResult() {
    this._lastResult.set(null);
  }

  update(id: string, patch: AssetPatch) {
    this.track(id, this.api.updateAsset(id, patch));
  }

  publish(id: string) {
    this.track(id, this.api.publishAsset(id));
  }

  refreshStorage() {
    this.api.listStoredObjects().subscribe((objects) => this._objects.set(objects));
  }

  getStoredObject(objectName: string) {
    return this.api.getStoredObject(objectName);
  }

  private track(id: string, call: Observable<GeneratedAsset>) {
    this._busyIds.update((s) => new Set(s).add(id));
    call.subscribe({
      next: (asset) => this.replace(asset),
      complete: () => this.release(id),
      error: () => this.release(id),
    });
  }

  private replace(asset: GeneratedAsset) {
    this._assets.update((list) => list.map((a) => (a.id === asset.id ? asset : a)));
    // Keep the ingestion result view in sync with curation edits
    this._lastResult.update((r) =>
      r ? { ...r, assets: r.assets.map((a) => (a.id === asset.id ? asset : a)) } : r,
    );
  }

  private release(id: string) {
    this._busyIds.update((s) => {
      const next = new Set(s);
      next.delete(id);
      return next;
    });
  }
}
