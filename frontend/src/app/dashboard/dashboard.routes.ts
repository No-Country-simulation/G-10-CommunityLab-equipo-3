import { Routes } from '@angular/router';
import { DashboardLayout } from './layouts/dashboard-layout/dashboard-layout';
import { Summary } from './pages/summary/summary';
import { Ingest } from './pages/ingest/ingest';
import { Content } from './pages/content/content';
import { Storage } from './pages/storage/storage';

/** One page per step of the challenge: ingest+analyze → curate → store in OCI. */
export const dashboardRoutes: Routes = [
  {
    path: '',
    component: DashboardLayout,
    children: [
      { path: '', component: Summary, title: 'Resumen · Kora', data: { title: 'Resumen' } },
      { path: 'ingest', component: Ingest, title: 'Ingesta · Kora', data: { title: 'Ingesta y análisis' } },
      { path: 'content', component: Content, title: 'Curaduría · Kora', data: { title: 'Curaduría' } },
      { path: 'storage', component: Storage, title: 'OCI Object Storage · Kora', data: { title: 'OCI Object Storage' } },
      { path: '**', redirectTo: '' },
    ],
  },
];

export default dashboardRoutes;
