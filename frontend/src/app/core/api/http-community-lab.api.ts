import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  AssetPatch,
  GeneratedAsset,
  ProcessRequest,
  ProcessResult,
  StoredObject,
} from './api.models';
import { API_CONFIG, CommunityLabApi } from './community-lab.api';

/** Real backend client. Enabled with `useMocks: false` in app.config.ts. */
@Injectable()
export class HttpCommunityLabApi extends CommunityLabApi {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_CONFIG).baseUrl;

  processInteractions(req: ProcessRequest): Observable<ProcessResult> {
    return this.http.post<ProcessResult>(`${this.base}/interactions/process`, req);
  }

  listAssets(): Observable<GeneratedAsset[]> {
    return this.http.get<GeneratedAsset[]>(`${this.base}/assets`);
  }

  updateAsset(id: string, patch: AssetPatch): Observable<GeneratedAsset> {
    return this.http.patch<GeneratedAsset>(`${this.base}/assets/${id}`, patch);
  }

  publishAsset(id: string): Observable<GeneratedAsset> {
    return this.http.post<GeneratedAsset>(`${this.base}/assets/${id}/publish`, {});
  }

  listStoredObjects(): Observable<StoredObject[]> {
    return this.http.get<StoredObject[]>(`${this.base}/storage/objects`);
  }

  getStoredObject(objectName: string): Observable<ProcessResult> {
    return this.http.get<ProcessResult>(
      `${this.base}/storage/objects/${encodeURIComponent(objectName)}`,
    );
  }
}
