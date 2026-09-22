import { InjectionToken } from '@angular/core';
import { Observable } from 'rxjs';
import {
  AssetPatch,
  GeneratedAsset,
  ProcessRequest,
  ProcessResult,
  StoredObject,
} from './api.models';

export interface ApiConfig {
  /** Base URL of the Spring Boot API, e.g. http://localhost:8080/api/v1 */
  baseUrl: string;
  /** true → simulated responses (no backend needed for the demo) */
  useMocks: boolean;
}

export const API_CONFIG = new InjectionToken<ApiConfig>('API_CONFIG');

/** Every call the UI makes to the backend. Swap implementations via `useMocks`. */
export abstract class CommunityLabApi {
  abstract processInteractions(req: ProcessRequest): Observable<ProcessResult>;
  abstract listAssets(): Observable<GeneratedAsset[]>;
  abstract updateAsset(id: string, patch: AssetPatch): Observable<GeneratedAsset>;
  abstract publishAsset(id: string): Observable<GeneratedAsset>;
  abstract listStoredObjects(): Observable<StoredObject[]>;
  abstract getStoredObject(objectName: string): Observable<ProcessResult>;
}
