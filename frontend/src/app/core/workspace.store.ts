import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable } from 'rxjs';
import {
  AssetPatch,
  AssetStatus,
  GeneratedAsset,
  INTERACTION_SOURCES,
  InteractionSource,
  StoredObject,
} from './api/api.models';
import { CommunityLabApi } from './api/community-lab.api';
import { DiscordLiveFeed, LIVE_ASSET_PREFIX } from './api/discord-live-feed';

const SOURCE_KEY = 'active-source';

/** Shared state for curation and OCI storage, backed by CommunityLabApi. */
@Injectable({ providedIn: 'root' })
export class WorkspaceStore {
  private readonly api = inject(CommunityLabApi);
  private readonly liveFeed = inject(DiscordLiveFeed);

  private readonly _assets = signal<GeneratedAsset[]>([]);
  private readonly _objects = signal<StoredObject[]>([]);
  private readonly _busyIds = signal<Set<string>>(new Set());
  private readonly _activeSource = signal<InteractionSource>(readActiveSource());

  /** Network the workspace is scoped to (Discord or Telegram). */
  readonly activeSource = this._activeSource.asReadonly();

  /** Assets of the selected network: what every page lists and counts. */
  readonly assets = computed(() => {
    const source = this._activeSource();
    return this._assets().filter((a) => a.origin.source === source);
  });

  /** Asset count per network, for the network switcher. */
  readonly sourceCounts = computed(() => {
    const counts = Object.fromEntries(INTERACTION_SOURCES.map((s) => [s, 0])) as Record<InteractionSource, number>;
    for (const a of this._assets()) counts[a.origin.source]++;
    return counts;
  });

  readonly objects = this._objects.asReadonly();
  readonly loaded = signal(false);

  readonly pipeline = computed(() => {
    const order: AssetStatus[] = ['draft', 'in_review', 'approved', 'published'];
    const list = this.assets();
    return order.map((status) => ({ status, count: list.filter((a) => a.status === status).length }));
  });

  readonly pendingReview = computed(
    () => this.assets().filter((a) => a.status === 'draft' || a.status === 'in_review').length,
  );

  setActiveSource(source: InteractionSource) {
    this._activeSource.set(source);
    try {
      localStorage.setItem(SOURCE_KEY, source);
    } catch {
      // selection just won't persist
    }
  }

  constructor() {
    this.api.listAssets().subscribe({
      // Keep assets the live feed may have pushed while the list was loading
      next: (assets) => this._assets.update((live) => [...live, ...assets]),
      // Endpoint missing or backend down: start empty and rely on the live feed
      error: () => this.loaded.set(true),
      complete: () => this.loaded.set(true),
    });
    this.refreshStorage();
    this.liveFeed.assets.subscribe((asset) => this.upsert(asset));
    this.liveFeed.connect();
  }

  /** Connection state of the backend SSE stream. */
  readonly liveStatus = this.liveFeed.status;

  isBusy(id: string) {
    return this._busyIds().has(id);
  }

  update(id: string, patch: AssetPatch) {
    if (id.startsWith(LIVE_ASSET_PREFIX)) return this.patchLocal(id, patch);
    this.track(id, this.api.updateAsset(id, patch));
  }

  publish(id: string) {
    if (id.startsWith(LIVE_ASSET_PREFIX)) {
      return this.patchLocal(id, { status: 'published' }, new Date().toISOString());
    }
    this.track(id, this.api.publishAsset(id));
  }

  refreshStorage() {
    this.api.listStoredObjects().subscribe({
      next: (objects) => this._objects.set(objects),
      // No storage endpoint yet: keep the current (empty) list
      error: () => {},
    });
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

  /** Live assets from the SSE stream: newest first, replayed events replace the old copy. */
  private upsert(asset: GeneratedAsset) {
    this._assets.update((list) => [asset, ...list.filter((a) => a.id !== asset.id)]);
  }

  /** The backend has no PATCH/publish yet, so curation of live assets stays in the browser. */
  private patchLocal(id: string, patch: AssetPatch, publishedAt?: string) {
    const current = this._assets().find((a) => a.id === id);
    if (!current) return;
    this.replace({ ...current, ...patch, ...(publishedAt ? { publishedAt } : {}) });
  }

  private replace(asset: GeneratedAsset) {
    this._assets.update((list) => list.map((a) => (a.id === asset.id ? asset : a)));
  }

  private release(id: string) {
    this._busyIds.update((s) => {
      const next = new Set(s);
      next.delete(id);
      return next;
    });
  }
}

function readActiveSource(): InteractionSource {
  try {
    const saved = localStorage.getItem(SOURCE_KEY);
    return INTERACTION_SOURCES.find((s) => s === saved) ?? 'Discord';
  } catch {
    return 'Discord';
  }
}
