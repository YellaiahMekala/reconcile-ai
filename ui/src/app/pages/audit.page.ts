import { Component, inject, signal } from '@angular/core';
import { ApiService, errorText } from '../core/api.service';
import { ChainVerification } from '../core/models';
import { StatusBadge } from './status-badge';

@Component({
  selector: 'app-audit',
  imports: [StatusBadge],
  template: `
    <h1>Audit chain verification</h1>
    <section class="card narrow">
      <p class="muted">Recomputes every row's SHA-256 hash and checks each prev_hash link (SRE_ADMIN only).</p>
      <button type="button" (click)="verify()">Verify now</button>
      @if (result(); as r) {
        <p><app-status [value]="r.valid ? 'VALID' : 'INVALID'" /> {{ r.eventsChecked }} events checked</p>
        @if (r.firstBrokenSequence !== null) {
          <p class="error">First broken sequence: {{ r.firstBrokenSequence }}</p>
        }
        <p class="mono">head {{ r.headHash }}</p>
      }
      @if (error()) {
        <p class="error">{{ error() }}</p>
      }
    </section>
  `,
})
export class AuditPage {
  private readonly api = inject(ApiService);
  readonly result = signal<ChainVerification | null>(null);
  readonly error = signal<string | null>(null);

  verify(): void {
    this.error.set(null);
    this.api.verifyAudit().subscribe({
      next: (r) => this.result.set(r),
      error: (e) => this.error.set(errorText(e)),
    });
  }
}
