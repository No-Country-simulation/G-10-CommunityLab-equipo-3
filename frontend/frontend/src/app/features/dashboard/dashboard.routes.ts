import { Routes } from '@angular/router';
import { DashboardLayout } from './layouts/dashboard-layout/dashboard-layout';
import { Summary } from './pages/summary/summary';
import { Content } from './pages/content/content';
import { Storage } from './pages/storage/storage';
import { PAGE_ICON } from '../../core/page-icons';

/** One page per step of the challenge: curate → store in OCI (ingestion and analysis run in the backend). */
export const dashboardRoutes: Routes = [
  {
    path: '',
    component: DashboardLayout,
    children: [
      { path: '', component: Summary, data: { titleKey: 'nav.summary', icon: PAGE_ICON.summary } },
      { path: 'content', component: Content, data: { titleKey: 'nav.content', icon: PAGE_ICON.content } },
      { path: 'storage', component: Storage, data: { titleKey: 'nav.storage', icon: PAGE_ICON.storage } },
      { path: '**', redirectTo: '' },
    ],
  },
];

export default dashboardRoutes;
