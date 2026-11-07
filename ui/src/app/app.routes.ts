import { Routes } from '@angular/router';
import { authGuard, roleGuard } from './core/auth.guard';
import { LoginPage } from './pages/login.page';

export const routes: Routes = [
  { path: 'login', component: LoginPage },
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
