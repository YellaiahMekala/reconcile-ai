import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService, errorText } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { BreakItem } from '../core/models';
import { StatusBadge } from './status-badge';

const AMOUNT = /^-?\d+(\.\d+)?$/;

@Component({
  selector: 'app-breaks',
  imports: [ReactiveFormsModule, RouterLink, StatusBadge],
  template: `
    <h1>Reconciliation breaks</h1>
    @if (auth.hasRole('RECON_ANALYST')) {
      <form class="card row" [formGroup]="form" (ngSubmit)="create()">
        <label>Reference <input formControlName="breakReference" placeholder="BRK-1001" /></label>
        <label>Ledger amount <input formControlName="ledgerAmount" placeholder="50500.00" /></label>
        <label>External amount <input formControlName="externalAmount" placeholder="50000.00" /></label>
        <label>Currency <input formControlName="currency" maxlength="3" /></label>
        <button type="submit" [disabled]="form.invalid || busy()">Create break</button>
      </form>
    }
    @if (error()) {
      <p class="error">{{ error() }}</p>
    }
    <table class="card">
      <thead>
        <tr><th>Reference</th><th>Status</th><th class="num">Ledger</th><th class="num">External</th><th class="num">Variance</th><th>Created</th></tr>
      </thead>
      <tbody>
        @for (b of breaks(); track b.id) {
          <tr>
            <td><a [routerLink]="['/breaks', b.id]">{{ b.breakReference }}</a></td>
            <td><app-status [value]="b.status" /></td>
            <td class="num">{{ b.ledgerAmount }} {{ b.currency }}</td>
            <td class="num">{{ b.externalAmount }} {{ b.currency }}</td>
            <td class="num">{{ b.variance }}</td>
            <td>{{ b.createdAt.slice(0, 19).replace('T', ' ') }}</td>
          </tr>
        } @empty {
          <tr><td colspan="6" class="muted">No breaks for this tenant yet.</td></tr>
        }
      </tbody>
    </table>
  `,
})
export class BreaksPage implements OnInit {
  readonly auth = inject(AuthService);
  private readonly api = inject(ApiService);
  readonly breaks = signal<BreakItem[]>([]);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);

  readonly form = inject(FormBuilder).nonNullable.group({
    breakReference: ['', Validators.required],
    ledgerAmount: ['', [Validators.required, Validators.pattern(AMOUNT)]],
    externalAmount: ['', [Validators.required, Validators.pattern(AMOUNT)]],
    currency: ['USD', [Validators.required, Validators.pattern(/^[A-Z]{3}$/)]],
  });

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.api.listBreaks().subscribe({
      next: (b) => this.breaks.set(b),
      error: (e) => this.error.set(errorText(e)),
    });
  }

  create(): void {
    this.busy.set(true);
    this.error.set(null);
    this.api.createBreak(this.form.getRawValue()).subscribe({
      next: () => {
        this.busy.set(false);
        this.form.patchValue({ breakReference: '', ledgerAmount: '', externalAmount: '' });
        this.load();
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(errorText(e));
      },
    });
  }
}
