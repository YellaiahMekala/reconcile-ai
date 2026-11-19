// Copyright (c) 2026 the owner of this repository. All rights reserved.
// SPDX-License-Identifier: LicenseRef-Proprietary. View only; see LICENSE and AI_USE_POLICY.md.
// Canary: reconcile-ai-canary-984de7f19b5e49fd803a
import { Routes } from '@angular/router';
import { authGuard, roleGuard } from './core/auth.guard';
import { LoginPage } from './pages/login.page';

export const routes: Routes = [
  { path: 'login', component: LoginPage },
  { path: 'auth/callback', loadComponent: () => import('./pages/auth-callback.page').then((m) => m.AuthCallbackPage) },
  {
    path: 'breaks',
    canActivate: [authGuard],
    loadComponent: () => import('./pages/breaks.page').then((m) => m.BreaksPage),
  },
  {
    path: 'breaks/:id',
    canActivate: [authGuard],
    loadComponent: () => import('./pages/break-detail.page').then((m) => m.BreakDetailPage),
  },
  {
    path: 'approvals',
    canActivate: [authGuard, roleGuard],
    data: { roles: ['APPROVER_L1', 'APPROVER_L2'] },
    loadComponent: () => import('./pages/approvals.page').then((m) => m.ApprovalsPage),
  },
  {
    path: 'audit',
    canActivate: [authGuard, roleGuard],
    data: { roles: ['SRE_ADMIN'] },
    loadComponent: () => import('./pages/audit.page').then((m) => m.AuditPage),
  },
  { path: '', pathMatch: 'full', redirectTo: 'breaks' },
  { path: '**', redirectTo: 'breaks' },
];
