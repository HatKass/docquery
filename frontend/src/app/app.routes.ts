import { Routes } from '@angular/router';
import { adminGuard } from './admin/admin.guard';
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
    path: 'workspaces/:workspaceId/chat',
    canActivate: [authGuard],
    loadComponent: () => import('./chat/chat.page').then((m) => m.ChatPage),
    title: 'Ask · DocQuery',
  },
  {
    path: 'workspaces/:workspaceId/members',
    canActivate: [authGuard],
    loadComponent: () => import('./workspaces/workspace-detail.page').then((m) => m.WorkspaceDetailPage),
    title: 'Members & settings · DocQuery',
  },
  {
    path: 'admin',
    canActivate: [authGuard, adminGuard],
    loadComponent: () => import('./admin/admin.page').then((m) => m.AdminPage),
    title: 'Admin · DocQuery',
  },
  { path: '**', redirectTo: '' },
];
