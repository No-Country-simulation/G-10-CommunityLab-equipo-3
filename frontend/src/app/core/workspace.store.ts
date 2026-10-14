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
import { MessageService } from 'primeng/api';
import { API_CONFIG, CommunityLabApi, RevisionError } from './api/community-lab.api';
import { LIVE_ASSET_PREFIX, LiveFeed, RevisionNotice, liveAssetId } from './api/live-feed';
import { OciPackages, PackageRef } from './api/oci-packages';
import { I18n } from './i18n/i18n.service';

const SOURCE_KEY = 'active-source';

/** How a curation change ended: stored, waiting for its package (kept in this browser), or refused */
export type SaveOutcome = 'saved' | 'pending' | 'failed';
/** How many recent messages decide the community mood */
const MOOD_WINDOW = 20;

/** Shared state for curation and OCI storage, backed by CommunityLabApi. */
@Injectable({ providedIn: 'root' })
export class WorkspaceStore {
  private readonly api = inject(CommunityLabApi);
  private readonly liveFeed = inject(LiveFeed);
  private readonly config = inject(API_CONFIG);
  private readonly packages = inject(OciPackages);
  private readonly toast = inject(MessageService);
  private readonly i18n = inject(I18n);

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

  /**
   * Every community message received, across both networks: one entry per message (a message can generate
   * several posts), with its network and when it was written. Feeds the summary charts.
   */
  readonly interactions = computed(() => {
    const byMessage = new Map<string, { source: InteractionSource; at: string }>();
    for (const a of this._assets()) {
      const key = `${a.origin.source}:${a.interactionId}`;
      if (!byMessage.has(key)) byMessage.set(key, { source: a.origin.source, at: a.createdAt });
    }
    return [...byMessage.values()];
  });

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
      // Merge, don't append: the live feed may already have pushed some of these posts
      next: (assets) => assets.forEach((a) => this.mergeFromServer(a)),
      // Endpoint missing or backend down: start empty and rely on the live feed
      error: () => this.loaded.set(true),
      complete: () => this.loaded.set(true),
    });
    this.refreshStorage();
    this.liveFeed.assets.subscribe((asset) => this.mergeFromServer(asset));
    this.liveFeed.packages.subscribe((notice) => {
      const ref = this.packages.add(notice);
      this.loadPackage(ref);
      // The history block can announce dozens of packages in a row: refresh the storage list once per burst
      clearTimeout(this.storageRefresh);
      this.storageRefresh = setTimeout(() => this.refreshStorage(), 300);
    });
    this.liveFeed.revisions.subscribe((n) => this.onRevised(n));
    this.liveFeed.connect();
  }

  private storageRefresh?: ReturnType<typeof setTimeout>;

  /** Whether the selected network already received its history from the backend (spec 008) */
  readonly historyLoaded = computed(() => this.liveFeed.historyLoaded()[this._activeSource()]);

  /** Waiting for the history of the selected network: show "loading" instead of an empty list */
  readonly historyLoading = computed(
    () =>
      !this.config.useMocks &&
      !!this.liveFeed.urlOf(this._activeSource()) &&
      this.liveStatus() !== 'error' &&
      !this.historyLoaded(),
  );

  /** Connection state of the selected network's SSE stream. */
  readonly liveStatus = computed(() => this.liveFeed.status()[this._activeSource()]);

  /** SSE endpoint of the selected network, if it has one. */
  readonly liveUrl = computed(() => this.liveFeed.urlOf(this._activeSource()));

  isBusy(id: string) {
    return this._busyIds().has(id);
  }

  /** Resolves when the backend answered: the page only confirms a change that was really saved. */
  update(id: string, patch: AssetPatch): Promise<SaveOutcome> {
    const asset = this.find(id);
    if (!asset) return Promise.resolve('failed');
    if (!id.startsWith(LIVE_ASSET_PREFIX)) return this.track(id, this.api.updateAsset(asset, patch));
    // Live posts change on screen right away; the backend gets the change in the background
    this.replace({ ...asset, ...patch });
    return this.save(asset, patch, this.api.updateAsset(asset, patch));
  }

  publish(id: string): Promise<SaveOutcome> {
    const asset = this.find(id);
    if (!asset) return Promise.resolve('failed');
    if (!id.startsWith(LIVE_ASSET_PREFIX)) return this.track(id, this.api.publishAsset(asset));
    this.replace({ ...asset, status: 'published', publishedAt: new Date().toISOString() });
    return this.save(asset, { status: 'published' }, this.api.publishAsset(asset));
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

  /**
   * Changes made before the post's batch reached OCI: the backend can only revise stored posts,
   * so they wait here and are sent as soon as the package arrives (`package.completed`).
   */
  private readonly pending = new Map<string, AssetPatch>();

  /** Sends a live post's change; `before` is the post as the backend last saw it. */
  private save(before: GeneratedAsset, patch: AssetPatch, call: Observable<GeneratedAsset>): Promise<SaveOutcome> {
    this._busyIds.update((s) => new Set(s).add(before.id));
    return new Promise((resolve) =>
      call.subscribe({
        next: (saved) => {
          this.pending.delete(before.id);
          // Keep what the CM sees, take the new version from the backend
          const current = this.find(before.id) ?? saved;
          this.replace({ ...current, versionMessage: saved.versionMessage, status: saved.status });
          this.release(before.id);
          resolve('saved');
        },
        error: (err: unknown) => {
          this.release(before.id);
          const reason = err instanceof RevisionError ? err.reason : 'unavailable';
          if (reason === 'not-packaged') {
            this.pending.set(before.id, { ...this.pending.get(before.id), ...patch });
            this.notify('info', 'sync.pending');
            return resolve('pending');
          }
          resolve('failed');
          if (reason === 'conflict') {
            // Someone saved first: show their version
            this.notify('warn', 'sync.conflict');
            return this.reloadPackageOf(before);
          }
          // Rejected by the backend: back to what is stored, with its explanation
          this.replace(before);
          const detail = err instanceof RevisionError ? err.detail : undefined;
          this.notify('error', `sync.${reason}`, detail);
        },
      }),
    );
  }

  private loadPackage(ref: PackageRef) {
    this.packages.loadAssets(ref).subscribe({
      next: (assets) => assets.forEach((a) => this.mergeFromServer(a)),
      error: () => this.notify('error', 'sync.packageUnreadable'),
    });
  }

  /** Re-reads the package that stores this post (after a conflict or someone else's revision) */
  private reloadPackageOf(asset: Pick<GeneratedAsset, 'origin' | 'packageId'>) {
    const ref = asset.packageId ? this.packages.find(asset.origin.source, asset.packageId) : undefined;
    if (!ref) return;
    this.packages.invalidate(ref);
    this.loadPackage(ref);
  }

  private onRevised(n: RevisionNotice) {
    const asset = this.find(liveAssetId(n.source, n.messageId));
    // Our own save echoing back, or an older event replayed on reconnect
    if (asset && (asset.versionMessage ?? 0) >= n.versionMessage) return;
    const ref = this.packages.find(n.source, n.batchId);
    if (ref) {
      this.packages.invalidate(ref);
      this.loadPackage(ref);
    } else if (asset) {
      // Package not known to this browser: at least reflect the approval
      this.replace({ ...asset, versionMessage: n.versionMessage, status: n.approved ? 'approved' : 'in_review' });
    }
  }

  /**
   * A post from the stream or from an OCI package. New posts are added (newest first); known ones take the
   * backend copy, except for what only this browser knows: unsent changes, "published" and "rejected"
   * (the backend only stores approved yes/no).
   */
  private mergeFromServer(incoming: GeneratedAsset) {
    const current = this.find(incoming.id);
    if (!current) {
      this._assets.update((list) => [incoming, ...list]);
      return;
    }
    if ((incoming.versionMessage ?? 0) < (current.versionMessage ?? 0)) return;
    const waiting = this.pending.get(incoming.id);
    const status =
      current.status === 'published' && incoming.status === 'approved' ? 'published'
        : current.status === 'rejected' && incoming.status === 'in_review' ? 'rejected'
          : incoming.status;
    const stored: GeneratedAsset = {
      ...incoming,
      status,
      publishedAt: current.publishedAt,
      packageId: incoming.packageId ?? current.packageId,
    };
    // What the CM sees: the stored post plus the changes still waiting to be sent
    this.replace({ ...stored, ...waiting });
    // The batch just reached OCI: send what the CM changed meanwhile
    if (waiting && stored.packageId && !current.packageId) {
      this.pending.delete(incoming.id);
      this.save(stored, waiting, this.api.updateAsset(stored, waiting));
    }
  }

  private find(id: string) {
    return this._assets().find((a) => a.id === id);
  }

  private notify(severity: 'info' | 'warn' | 'error', key: string, detail?: string) {
    this.toast.add({
      severity,
      summary: this.i18n.t(key),
      detail: detail ?? this.i18n.t(`${key}Detail`),
      life: severity === 'error' ? 6000 : 4000,
    });
  }

  private track(id: string, call: Observable<GeneratedAsset>): Promise<SaveOutcome> {
    this._busyIds.update((s) => new Set(s).add(id));
    return new Promise((resolve) =>
      call.subscribe({
        next: (asset) => this.replace(asset),
        complete: () => {
          this.release(id);
          resolve('saved');
        },
        error: () => {
          this.release(id);
          resolve('failed');
        },
      }),
    );
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
