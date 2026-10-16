import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, forkJoin, map, of, throwError } from 'rxjs';
import { AssetPatch, GeneratedAsset, StorageStats, StoredObject } from './api.models';
import { API_CONFIG, CommunityLabApi, RevisionError, RevisionFailure, StoredObjectView } from './community-lab.api';
import { ApprovalState, CHANNEL_POST, toApprovalStateOf, toAssetStatus } from './live-feed';
import { OciPackages, PackageRef } from './oci-packages';

/** Body of PATCH /api/v1/{source}/packages/{batchId}/messages/{messageId}: only what changed, plus the version */
interface RevisePostRequest {
  expectedVersion: number;
  approved?: ApprovalState;
  /** When the post was published; needs approved=APPROVED, can't be in the future nor cleared */
  publishedAt?: string;
  channelPost?: string;
  titlePost?: string;
  outputContentProcessed?: string;
  hashtags?: string[];
  cta?: string;
}

interface RevisePostResponse {
  batchId: string;
  messageId: string;
  versionMessage: number;
  approved: ApprovalState;
}

const FAILURE_BY_STATUS: Record<number, RevisionFailure> = { 400: 'invalid', 404: 'not-found', 409: 'conflict' };

/** How far back publishedAt is dated, so a browser clock slightly ahead of the server's isn't refused */
const CLOCK_MARGIN_MS = 5000;

/**
 * Real backend client. Enabled with `useMocks: false` in app.config.ts.
 * The backend exposes the SSE streams and the post revision PATCH; stored packages are read straight
 * from OCI through the PAR announced by `package.completed` (see OciPackages).
 */
@Injectable()
export class HttpCommunityLabApi extends CommunityLabApi {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_CONFIG).baseUrl;
  private readonly packages = inject(OciPackages);

  /** Posts of every package this browser knows; a package whose PAR no longer works is forgotten. */
  listAssets(): Observable<GeneratedAsset[]> {
    const refs = this.packages.refs();
    if (!refs.length) return of([]);
    return forkJoin(
      refs.map((ref) =>
        this.packages.loadAssets(ref).pipe(
          catchError((err: HttpErrorResponse) => {
            if (err.status === 403 || err.status === 404) this.packages.drop(ref);
            return of([] as GeneratedAsset[]);
          }),
        ),
      ),
    ).pipe(map((lists) => lists.flat()));
  }

  updateAsset(asset: GeneratedAsset, patch: AssetPatch): Observable<GeneratedAsset> {
    if (!asset.packageId) return throwError(() => new RevisionError('not-packaged'));
    const source = asset.origin.source.toLowerCase();
    const url = `${this.base}/${source}/packages/${asset.packageId}/messages/${asset.interactionId}`;
    const revision = toRevision(asset, patch);
    return this.http.patch<RevisePostResponse>(url, revision).pipe(
      map((res) => {
        const publishedAt = revision.publishedAt ?? asset.publishedAt;
        return {
          ...asset,
          ...patch,
          versionMessage: res.versionMessage,
          publishedAt,
          status: toAssetStatus(res.approved, !!publishedAt),
        };
      }),
      catchError((err: HttpErrorResponse) => {
        const detail = typeof err.error?.detail === 'string' ? err.error.detail : undefined;
        return throwError(() => new RevisionError(FAILURE_BY_STATUS[err.status] ?? 'unavailable', detail));
      }),
    );
  }

  /** Publishing is manual (the CM posts it on the channel): the backend stores it as APPROVED + publishedAt. */
  publishAsset(asset: GeneratedAsset): Observable<GeneratedAsset> {
    return this.updateAsset(asset, { status: 'published' });
  }

  listStoredObjects(): Observable<StoredObject[]> {
    const refs = this.packages.refs();
    if (!refs.length) return of([]);
    return forkJoin(
      refs.map((ref) =>
        this.packages.loadRaw(ref).pipe(
          map(({ pkg, sizeBytes }) => toStoredObject(ref, sizeBytes, pkg.generatedAt)),
          // Unreadable right now: still listed, size unknown
          catchError(() => of(toStoredObject(ref, 0))),
        ),
      ),
    ).pipe(map((list) => list.sort((a, b) => b.storedAt.localeCompare(a.storedAt))));
  }

  getStoredObject(objectName: string): Observable<StoredObjectView> {
    const ref = this.packages.refs().find((r) => objectNameOf(r) === objectName);
    if (!ref) return throwError(() => new Error('Paquete no encontrado'));
    return this.packages.load(ref).pipe(map((content) => ({ objectName, content })));
  }

  getStorageStats(): Observable<StorageStats> {
    return this.http.get<StorageStats>(`${this.base}/storage/stats`);
  }
}

/** Object name inside the bucket prefix, as the backend writes it */
const objectNameOf = (ref: PackageRef) => `${ref.source.toLowerCase()}/paquete-${ref.batchId}.json`;

/** When the package was stored: its own `generatedAt`, else its PAR expiry minus the 3-day TTL */
const PAR_TTL_MS = 3 * 864e5;

function toStoredObject(ref: PackageRef, sizeBytes: number, generatedAt?: string): StoredObject {
  return {
    objectName: objectNameOf(ref),
    batchId: ref.batchId,
    sizeBytes,
    storedAt: generatedAt ?? new Date(Date.parse(ref.expiresAt) - PAR_TTL_MS).toISOString(),
    interactions: ref.assetsCount,
    assets: ref.assetsCount,
    // Never the PAR: it is a secret. The object path is enough to find it in the OCI console.
    url: `paquetes/${objectNameOf(ref)}`,
  };
}

/** UI patch → backend revision: approval flag and the edited fields, nothing else. */
function toRevision(asset: GeneratedAsset, patch: AssetPatch): RevisePostRequest {
  const body: RevisePostRequest = { expectedVersion: asset.versionMessage ?? 1 };
  if (patch.status) body.approved = toApprovalStateOf(patch.status);
  // A little in the past: the backend refuses a publishedAt ahead of its own clock
  if (patch.status === 'published' && !asset.publishedAt) body.publishedAt = new Date(Date.now() - CLOCK_MARGIN_MS).toISOString();
  if (patch.title !== undefined) body.titlePost = patch.title;
  if (patch.hashtags !== undefined) body.hashtags = patch.hashtags;
  if (patch.type !== undefined && patch.type !== asset.type) body.channelPost = CHANNEL_POST[patch.type];
  if (patch.cta !== undefined) body.cta = patch.cta;
  if (patch.body !== undefined) {
    // The UI shows the call to action as the body's last paragraph; the backend keeps it apart (required on LinkedIn)
    const cta = patch.cta ?? asset.cta;
    const suffix = cta ? `\n\n${cta}` : '';
    body.outputContentProcessed = suffix && patch.body.endsWith(suffix) ? patch.body.slice(0, -suffix.length) : patch.body;
  }
  return body;
}
