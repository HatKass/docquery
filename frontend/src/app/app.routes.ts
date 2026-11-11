import { Routes } from '@angular/router';
import { authGuard } from './auth/auth.guard';

export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    loadComponent: () => import('./home.page').then((m) => m.HomePage),
    title: 'DocQuery',
  },
  {
    path: 'auth/callback',
    loadComponent: () => import('./auth/callback.page').then((m) => m.CallbackPage),
    title: 'Signing in · DocQuery',
  },
  {
    path: 'status',
    loadComponent: () => import('./system/system-status').then((m) => m.SystemStatusPage),
    title: 'System status · DocQuery',
  },
  {
    path: 'workspaces',
    canActivate: [authGuard],
    loadComponent: () => import('./workspaces/workspaces.page').then((m) => m.WorkspacesPage),
    title: 'Workspaces · DocQuery',
  },
  {
    path: 'workspaces/:workspaceId',
    canActivate: [authGuard],
    loadComponent: () => import('./documents/document-library.page').then((m) => m.DocumentLibraryPage),
    title: 'Documents · DocQuery',
  },
  {
    path: 'workspaces/:workspaceId/members',
    canActivate: [authGuard],
    loadComponent: () => import('./workspaces/workspace-detail.page').then((m) => m.WorkspaceDetailPage),
    title: 'Members & settings · DocQuery',
  },
  { path: '**', redirectTo: '' },
];
