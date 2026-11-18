import { Component, computed, input } from '@angular/core';

/** Colour-coded status pill. Tone is derived from the status name, so new statuses still render. */
@Component({
  selector: 'app-status',
  template: `<span [class]="'badge ' + tone()">{{ value() }}</span>`,
})
export class StatusBadge {
  readonly value = input.required<string>();
  readonly tone = computed(() => {
    const v = this.value();
    if (/FAIL|REJECT|QUARANTIN|BLOCK|INVALID|DENIED|ERROR/.test(v)) return 'bad';
    if (/RESOLVED|SUCCEEDED|APPROVED|READY|VALID|^OK$/.test(v)) return 'ok';
    if (/PENDING|RUNNING|QUEUED|PROCESSING|MANUAL|INVESTIGAT|LIMIT|FILTERED|UNAVAILABLE|UNKNOWN/.test(v)) return 'warn';
    return 'neutral';
  });
}
