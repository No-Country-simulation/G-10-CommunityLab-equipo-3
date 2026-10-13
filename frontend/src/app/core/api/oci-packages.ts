import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, map } from 'rxjs';
import { GeneratedAsset, InteractionSource } from './api.models';
import { BackendPost, PackageNotice, toGeneratedAsset } from './live-feed';

/** Package JSON stored in OCI (`paquetes/{source}/paquete-{batchId}.json`), read straight from its PAR. */
export interface BackendPackage {
  batchId: string;
  source: 'DISCORD' | 'TELEGRAM';
  generatedAt: string;
  /** One record per message: the post plus its analysis (`LLM_FALLBACK` traces included) */
  enriched: BackendPost[];
  stats?: { received: number; analyzed: number; fallbackCount: number; assetsCount: number };
  promptVersion?: string;
}

/** What the browser keeps of a `package.completed` notice. */
export interface PackageRef extends PackageNotice {
  /** When the notice arrived: the package was stored then */
  storedAt: string;
}

const STORAGE_KEY = 'oci-packages.v1';

/**
 * OCI packages announced by `package.completed`. The backend exposes no "list packages" endpoint, so the
 * notices are kept in this browser until their PAR expires (3 days): that is what lets curated posts and the
 * storage page survive a page reload. The PAR is a secret: kept only here, never logged or shown.
 */
@Injectable({ providedIn: 'root' })
export class OciPackages {
  private readonly http = inject(HttpClient);
  private readonly _refs = signal<PackageRef[]>(readRefs());

  /** Known packages that can still be read, newest first */
  readonly refs = this._refs.asReadonly();

  add(notice: PackageNotice): PackageRef {
    const ref: PackageRef = { ...notice, storedAt: new Date().toISOString() };
    // A re-flush of the same batch comes with a new PAR: keep only the latest one
    this._refs.update((refs) => [ref, ...refs.filter((r) => !sameBatch(r, ref))]);
    this.persist();
    return ref;
  }

  find(source: InteractionSource, batchId: string): PackageRef | undefined {
    return this._refs().find((r) => r.source === source && r.batchId === batchId);
  }

  /** GET the package straight from OCI (no backend call, no credentials besides the PAR). */
  load(ref: PackageRef): Observable<BackendPackage> {
    return this.http.get<BackendPackage>(ref.parUrlBase);
  }

  /** The package's posts, ready for the UI. */
  loadAssets(ref: PackageRef): Observable<GeneratedAsset[]> {
    return this.load(ref).pipe(map((pkg) => pkg.enriched.map((post) => toGeneratedAsset(post, ref.source, ref.batchId))));
  }

  /** Forget a package whose PAR stopped working (expired or revoked) */
  drop(ref: PackageRef) {
    this._refs.update((refs) => refs.filter((r) => !sameBatch(r, ref)));
    this.persist();
  }

  private persist() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(this._refs()));
    } catch {
      // packages just won't survive a reload
    }
  }
}

const sameBatch = (a: PackageRef, b: PackageRef) => a.source === b.source && a.batchId === b.batchId;

/** Saved notices whose PAR has not expired yet */
function readRefs(): PackageRef[] {
  try {
    const refs = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '[]') as PackageRef[];
    const now = Date.now();
    return Array.isArray(refs) ? refs.filter((r) => r?.parUrlBase && Date.parse(r.expiresAt) > now) : [];
  } catch {
    return [];
  }
}
