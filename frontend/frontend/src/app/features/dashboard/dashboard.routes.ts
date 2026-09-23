import { Routes } from '@angular/router';
import { DashboardLayout } from './layouts/dashboard-layout/dashboard-layout';
import { Summary } from './pages/summary/summary';
import { Ingest } from './pages/ingest/ingest';
import { Content } from './pages/content/content';
import { Storage } from './pages/storage/storage';
import { PAGE_ICON } from '../../core/page-icons';

/** One page per step of the challenge: ingest+analyze → curate → store in OCI. */
export const dashboardRoutes: Routes = [
  {
    path: '',
    component: DashboardLayout,
    children: [
      { path: '', component: Summary, data: { titleKey: 'nav.summary', icon: PAGE_ICON.summary } },
      { path: 'ingest', component: Ingest, data: { titleKey: 'nav.ingest', icon: PAGE_ICON.ingest } },
      { path: 'content', component: Content, data: { titleKey: 'nav.content', icon: PAGE_ICON.content } },
      { path: 'storage', component: Storage, data: { titleKey: 'nav.storage', icon: PAGE_ICON.storage } },
      { path: '**', redirectTo: '' },
    ],
  },
];

export default dashboardRoutes;
