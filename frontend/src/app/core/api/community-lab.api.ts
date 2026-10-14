import { InjectionToken } from '@angular/core';
import { Observable } from 'rxjs';
import {
  AssetPatch,
  GeneratedAsset,
  InteractionSource,
  StoredObject,
} from './api.models';

export interface ApiConfig {
  /** Base URL of the Spring Boot API, e.g. http://localhost:8080/api/v1 */
  baseUrl: string;
  /** true → simulated responses (no backend needed for the demo) */
  useMocks: boolean;
  /**
   * SSE stream of processed posts per network (GET /api/v1/discord/messages,
   * GET /api/v1/telegram/messages). Works in both modes: live posts are added on top
   * of the mock/HTTP data.
   */
  liveFeeds?: Partial<Record<InteractionSource, string>>;
}

export const API_CONFIG = new InjectionToken<ApiConfig>('API_CONFIG');

/** A stored object opened from the storage page: its name and its JSON as stored */
export interface StoredObjectView {
  objectName: string;
  content: unknown;
}

/** Why a revision was not saved on the backend; the store turns it into a message for the CM. */
export type RevisionFailure =
  /** The post's batch is not stored in OCI yet: the change waits in the browser */
  | 'not-packaged'
  /** 409: someone saved a newer version first */
  | 'conflict'
  /** 400: invalid change or channel rule broken (e.g. too many hashtags for X) */
  | 'invalid'
  /** 404: package or post not found */
  | 'not-found'
  /** 413/422/502/503 or network: the backend could not save it */
  | 'unavailable';

export class RevisionError extends Error {
  constructor(
    readonly reason: RevisionFailure,
    /** ProblemDetail.detail from the backend, when it sent one */
    readonly detail?: string,
  ) {
    super(detail ?? reason);
  }
}

/** Every call the UI makes to the backend. Swap implementations via `useMocks`. */
export abstract class CommunityLabApi {
  abstract listAssets(): Observable<GeneratedAsset[]>;
  /** Saves a status change or edit; fails with a RevisionError when the backend cannot take it. */
  abstract updateAsset(asset: GeneratedAsset, patch: AssetPatch): Observable<GeneratedAsset>;
  abstract publishAsset(asset: GeneratedAsset): Observable<GeneratedAsset>;
  abstract listStoredObjects(): Observable<StoredObject[]>;
  abstract getStoredObject(objectName: string): Observable<StoredObjectView>;
}
