import { Routes } from '@angular/router';

export const landingRoutes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    loadComponent: () => import('./pages/landing-page/landing-page').then((m) => m.LandingPage),
  },
];

export { LandingPage as Landing } from './pages/landing-page/landing-page';
