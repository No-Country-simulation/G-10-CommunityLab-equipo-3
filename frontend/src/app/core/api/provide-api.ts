import { Provider } from '@angular/core';
import { API_CONFIG, ApiConfig, CommunityLabApi } from './community-lab.api';
import { HttpCommunityLabApi } from './http-community-lab.api';
import { MockCommunityLabApi } from './mock-community-lab.api';

export function provideCommunityLabApi(config: ApiConfig): Provider[] {
  return [
    { provide: API_CONFIG, useValue: config },
    { provide: CommunityLabApi, useClass: config.useMocks ? MockCommunityLabApi : HttpCommunityLabApi },
  ];
}
