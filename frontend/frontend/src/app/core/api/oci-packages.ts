import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, map, shareReplay } from 'rxjs';
import { GeneratedAsset, InteractionSource } from './api.models';
import { BackendPost, PackageNotice, toGeneratedAsset } from './live-feed';

/** Package JSON stored in OCI (`paquetes/{source}/paquete-{batchId}.json`), read straight from its PAR. */
export interface BackendPackage {
  batchId: string;
  source: 'DISCORD' | 'TELEGRAM';
  /** When the backend stored the package */
  generatedAt: string;
  /** One record per message: the post plus its analysis (`LLM_FALLBACK` traces included) */
  enriched: BackendPost[];
  stats?: { received: number; analyzed: number; fallbackCount: number; assetsCount: number };
  promptVersion?: string;
}

/** A package announced by `package.completed`, live or replayed in the history block. */
export type PackageRef = PackageNotice;

/**
 * OCI packages announced by `package.completed`. Every time a dashboard connects, the backend replays all packages
 * with a valid PAR (last 3 days, backend spec 008), so every tab and every user sees the same history: nothing is
 * stored in the browser. The PAR is a secret: kept only in memory, never logged or shown.
 */
@Injectable({ providedIn: 'root' })
export class OciPackages {
  private readonly http = inject(HttpClient);
  private readonly _refs = signal<PackageRef[]>([]);

  /** Known packages, newest announcement first */
  readonly refs = this._refs.asReadonly();

  /** One request per package version: the storage page and the curation list share it */
  private readonly cache = new Map<string, Observable<{ pkg: BackendPackage; sizeBytes: number }>>();

  /** Registers a notice; the same batch announced again (history replay, re-flush) replaces the old entry. */
  add(notice: PackageNotice): PackageRef {
    this._refs.update((refs) => [notice, ...refs.filter((r) => !sameBatch(r, notice))]);
    return notice;
  }

  find(source: InteractionSource, batchId: string): PackageRef | undefined {
    return this._refs().find((r) => r.source === source && r.batchId === batchId);
  }

  /** GET the package straight from OCI (no backend call, no credentials besides the PAR), with its size. */
  loadRaw(ref: PackageRef): Observable<{ pkg: BackendPackage; sizeBytes: number }> {
    let request = this.cache.get(ref.parUrlBase);
    if (!request) {
      // The PAR URL never changes while the package is revised: revalidate (ETag) so a revision is never read stale
      request = this.http.get(ref.parUrlBase, { responseType: 'text', cache: 'no-cache' }).pipe(
        map((text) => ({ pkg: JSON.parse(text) as BackendPackage, sizeBytes: new Blob([text]).size })),
        shareReplay({ bufferSize: 1, refCount: false }),
      );
      this.cache.set(ref.parUrlBase, request);
      // A failed read is not cached: the next attempt asks OCI again
      request.subscribe({ error: () => this.cache.delete(ref.parUrlBase) });
    }
    return request;
  }

  load(ref: PackageRef): Observable<BackendPackage> {
    return this.loadRaw(ref).pipe(map(({ pkg }) => pkg));
  }

  /** The package changed (a post was revised): the next read fetches the current version from OCI */
  invalidate(ref: PackageRef) {
    this.cache.delete(ref.parUrlBase);
  }

  /** The package's posts, ready for the UI. */
  loadAssets(ref: PackageRef): Observable<GeneratedAsset[]> {
    return this.load(ref).pipe(map((pkg) => pkg.enriched.map((post) => toGeneratedAsset(post, ref.source, ref.batchId))));
  }

  /** Forget a package whose PAR stopped working (expired or revoked) */
  drop(ref: PackageRef) {
    this._refs.update((refs) => refs.filter((r) => !sameBatch(r, ref)));
    this.cache.delete(ref.parUrlBase);
  }
}

const sameBatch = (a: PackageRef, b: PackageRef) => a.source === b.source && a.batchId === b.batchId;
