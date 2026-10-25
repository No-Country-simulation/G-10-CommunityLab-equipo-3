import { Injectable } from '@angular/core';
import { Observable, delay, of, throwError } from 'rxjs';
import {
  AssetPatch,
  AssetStatus,
  GeneratedAsset,
  ProcessRequest,
  ProcessResult,
  StoredObject,
} from './api.models';
import { CommunityLabApi } from './community-lab.api';
import { process } from './mock-engine';
import { SEED_BATCHES } from './sample-interactions';

// v3: sentiment values in English (positive/neutral/negative); older saved demos are ignored
export const MOCK_STORAGE_KEY = 'communitylab.mock-db.v3';

/**
 * In-memory backend used while the real API does not exist (`useMocks: true`).
 * State is mirrored to localStorage so a demo survives page reloads.
 */
@Injectable()
export class MockCommunityLabApi extends CommunityLabApi {
  private readonly batches = new Map<string, ProcessResult>();
  private assets: GeneratedAsset[] = [];

  constructor() {
    super();
    if (this.restore()) return;
    // Older batches already went through curation, so give them realistic statuses
    const seededStatus: AssetStatus[] = ['published', 'published', 'approved', 'in_review', 'draft'];
    SEED_BATCHES.forEach((b, bi) => {
      const result = process(b.interactions, undefined, b.processedAt);
      result.assets.forEach((a, ai) => {
        a.status = seededStatus[(ai + bi) % seededStatus.length];
        if (a.status === 'published') a.publishedAt = b.processedAt;
      });
      this.save(result);
    });
  }

  processInteractions(req: ProcessRequest): Observable<ProcessResult> {
    if (!req.interactions?.length) {
      return throwError(() => new Error('La solicitud no contiene interacciones.'));
    }
    const result = process(req.interactions, req.formats);
    this.save(result);
    // Latency similar to a real LLM round-trip, so the pipeline UI can be shown
    return of(clone(result)).pipe(delay(2600));
  }

  listAssets(): Observable<GeneratedAsset[]> {
    return of(clone(this.assets)).pipe(delay(250));
  }

  updateAsset(id: string, patch: AssetPatch): Observable<GeneratedAsset> {
    const asset = this.assets.find((a) => a.id === id);
    if (!asset) return throwError(() => new Error(`Asset ${id} no encontrado`));
    Object.assign(asset, patch);
    this.persist();
    return of(clone(asset)).pipe(delay(300));
  }

  publishAsset(id: string): Observable<GeneratedAsset> {
    const asset = this.assets.find((a) => a.id === id);
    if (asset) asset.publishedAt = new Date().toISOString();
    return this.updateAsset(id, { status: 'published' }).pipe(delay(500));
  }

  listStoredObjects(): Observable<StoredObject[]> {
    const objects = [...this.batches.values()]
      .map((r) => ({
        objectName: r.storage.objectName,
        batchId: r.batchId,
        sizeBytes: r.storage.sizeBytes,
        storedAt: r.storage.storedAt,
        interactions: r.summary.totalInteractions,
        assets: r.assets.length,
        url: r.storage.url,
      }))
      .sort((a, b) => b.storedAt.localeCompare(a.storedAt));
    return of(objects).pipe(delay(300));
  }

  getStoredObject(objectName: string): Observable<ProcessResult> {
    const found = [...this.batches.values()].find((r) => r.storage.objectName === objectName);
    return found ? of(clone(found)).pipe(delay(200)) : throwError(() => new Error('Objeto no encontrado'));
  }

  private save(result: ProcessResult) {
    // The bucket keeps an immutable snapshot; curation edits only touch the asset list
    this.batches.set(result.batchId, clone(result));
    this.assets = [...result.assets, ...this.assets];
    this.persist();
  }

  private persist() {
    try {
      localStorage.setItem(
        MOCK_STORAGE_KEY,
        JSON.stringify({ batches: [...this.batches.values()], assets: this.assets }),
      );
    } catch {
      // storage full or unavailable: keep working in memory
    }
  }

  private restore(): boolean {
    try {
      const raw = localStorage.getItem(MOCK_STORAGE_KEY);
      if (!raw) return false;
      const db = JSON.parse(raw) as { batches: ProcessResult[]; assets: GeneratedAsset[] };
      db.batches.forEach((b) => this.batches.set(b.batchId, b));
      this.assets = db.assets;
      return true;
    } catch {
      return false;
    }
  }
}

function clone<T>(value: T): T {
  return structuredClone(value);
}
