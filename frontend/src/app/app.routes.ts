import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./system/system-status').then((m) => m.SystemStatusPage),
    title: 'DocQuery',
  },
  { path: '**', redirectTo: '' },
];
