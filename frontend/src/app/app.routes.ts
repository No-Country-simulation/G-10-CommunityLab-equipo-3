import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    // Public landing page
    path: '',
    pathMatch: 'full',
    loadComponent: () => import('./features/landing/pages/landing-page/landing-page').then((m) => m.LandingPage),
  },
  {
    // The product: summary, ingestion, curation and OCI storage
    path: 'app',
    loadChildren: () => import('./features/dashboard/dashboard.routes').then((m) => m.dashboardRoutes),
  },
  { path: '**', redirectTo: '' },
];
