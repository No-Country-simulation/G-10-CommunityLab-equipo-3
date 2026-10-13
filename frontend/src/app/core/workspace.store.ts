import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable } from 'rxjs';
import {
  AssetPatch,
  AssetStatus,
  GeneratedAsset,
  INTERACTION_SOURCES,
  InteractionSource,
  Sentiment,
  StoredObject,
} from './api/api.models';
import { CommunityLabApi } from './api/community-lab.api';
import { LIVE_ASSET_PREFIX, LiveFeed } from './api/live-feed';

const SOURCE_KEY = 'active-source';
/** How many recent messages decide the community mood */
const MOOD_WINDOW = 20;

/** Shared state for curation and OCI storage, backed by CommunityLabApi. */
@Injectable({ providedIn: 'root' })
export class WorkspaceStore {
  private readonly api = inject(CommunityLabApi);
  private readonly liveFeed = inject(LiveFeed);

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

  /** Items waiting for review per network, for the network list in the sidebar. */
  readonly pendingBySource = computed(() => {
    const counts = Object.fromEntries(INTERACTION_SOURCES.map((s) => [s, 0])) as Record<InteractionSource, number>;
    for (const a of this._assets()) if (a.status === 'draft' || a.status === 'in_review') counts[a.origin.source]++;
    return counts;
  });

  /**
   * Mood of the selected network's community: sentiment of its latest messages (one vote per message,
   * not per generated post). `mood` is the most frequent sentiment; ties lean to neutral. Null without messages.
   */
  readonly communityMood = computed(() => {
    const messages = new Map<string, GeneratedAsset>();
    for (const a of this.assets()) if (!messages.has(a.interactionId)) messages.set(a.interactionId, a);
    const latest = [...messages.values()].sort((a, b) => b.createdAt.localeCompare(a.createdAt)).slice(0, MOOD_WINDOW);
    const counts: Record<Sentiment, number> = { positive: 0, neutral: 0, negative: 0 };
    for (const a of latest) counts[a.origin.sentiment]++;
    if (!latest.length) return null;
    // A sentiment wins only when it is strictly the most frequent; any tie reads as neutral
    const winners = (Object.keys(counts) as Sentiment[]).filter((s) => counts[s] === Math.max(...Object.values(counts)));
    const mood: Sentiment = winners.length === 1 ? winners[0] : 'neutral';
    return { mood, counts, total: latest.length };
  });

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

  /** Connection state of the selected network's SSE stream. */
  readonly liveStatus = computed(() => this.liveFeed.status()[this._activeSource()]);

  /** SSE endpoint of the selected network, if it has one. */
  readonly liveUrl = computed(() => this.liveFeed.urlOf(this._activeSource()));

  isBusy(id: string) {
    return this._busyIds().has(id);
  }

  update(id: string, patch: AssetPatch) {
    if (id.startsWith(LIVE_ASSET_PREFIX)) {
      this.patchLocal(id, patch);
      this.api.updateAsset(id, patch).subscribe({
        error: (err) => console.warn('[workspace] live asset update failed on backend', err),
      });
      return;
    }
    this.track(id, this.api.updateAsset(id, patch));
  }

  publish(id: string) {
    if (id.startsWith(LIVE_ASSET_PREFIX)) {
      this.patchLocal(id, { status: 'published' }, new Date().toISOString());
      this.api.publishAsset(id).subscribe({
        error: (err) => console.warn('[workspace] live asset publish failed on backend', err),
      });
      return;
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
