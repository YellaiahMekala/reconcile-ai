import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService, errorText } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { ApprovalTask } from '../core/models';
import { StatusBadge } from './status-badge';

/** Approval queue for L1/L2. Maker-checker and tier rules are enforced by the API; errors are shown verbatim. */
@Component({
  selector: 'app-approvals',
  imports: [FormsModule, RouterLink, StatusBadge],
  template: `
    <h1>Approval queue <span class="muted">({{ auth.persona()?.roles?.join(', ') }})</span></h1>
    @if (error()) {
      <p class="error">{{ error() }}</p>
    }
    @if (message()) {
      <p class="ok">{{ message() }}</p>
    }
    <table class="card">
      <thead><tr><th>Break</th><th>Tier</th><th>Maker</th><th>Status</th><th>Created</th><th>Decision</th></tr></thead>
      <tbody>
        @for (t of tasks(); track t.id) {
          <tr>
            <td><a [routerLink]="['/breaks', t.breakItemId]">open break</a></td>
            <td>{{ t.requiredRole }}</td>
            <td>{{ t.makerSubject }}</td>
            <td><app-status [value]="t.status" /></td>
            <td>{{ t.createdAt.slice(0, 19).replace('T', ' ') }}</td>
            <td class="row">
              <input [(ngModel)]="comments[t.id]" placeholder="Comment" />
              <button type="button" (click)="decide(t, 'APPROVE')">Approve</button>
              <button type="button" class="secondary" (click)="decide(t, 'REJECT')">Reject</button>
            </td>
          </tr>
        } @empty {
          <tr><td colspan="6" class="muted">Nothing waiting for your tier.</td></tr>
        }
      </tbody>
    </table>
  `,
})
export class ApprovalsPage implements OnInit {
  readonly auth = inject(AuthService);
  private readonly api = inject(ApiService);
  readonly tasks = signal<ApprovalTask[]>([]);
  readonly error = signal<string | null>(null);
  readonly message = signal<string | null>(null);
  comments: Record<string, string> = {};

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.api.approvals().subscribe({
      next: (t) => this.tasks.set(t),
      error: (e) => this.error.set(errorText(e)),
    });
  }

  decide(t: ApprovalTask, decision: 'APPROVE' | 'REJECT'): void {
    this.error.set(null);
    this.message.set(null);
    this.api.decide(t.id, decision, this.comments[t.id] ?? '').subscribe({
      next: (r) => {
        this.message.set(`${decision} recorded: task ${r.status}`);
        this.load();
      },
      error: (e) => this.error.set(errorText(e)),
    });
  }
}
