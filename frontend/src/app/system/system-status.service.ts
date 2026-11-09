import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';

/** Subset of the Spring Boot Actuator health response we display. */
export interface HealthResponse {
  status: string;
  components?: Record<string, { status: string }>;
}

export type SystemStatus =
  | { state: 'loading' }
  | { state: 'up' | 'down'; overall: string; components: { name: string; status: string }[] }
  | { state: 'unreachable'; message: string };

/**
 * Polls the api-service health endpoint (proxied to :8080 by the dev server, see proxy.conf.json).
 * Actuator answers 503 with a JSON body when a component is DOWN, so that is still a valid reading.
 */
@Injectable({ providedIn: 'root' })
export class SystemStatusService {
  private readonly http = inject(HttpClient);

  private readonly _status = signal<SystemStatus>({ state: 'loading' });
  readonly status = this._status.asReadonly();
  readonly isUp = computed(() => this._status().state === 'up');

  refresh(): void {
    this._status.set({ state: 'loading' });
    this.http.get<HealthResponse>('/actuator/health').subscribe({
      next: (health) => this._status.set(toStatus(health)),
      error: (err: HttpErrorResponse) => {
        if (err.status === 503 && err.error?.status) {
          this._status.set(toStatus(err.error as HealthResponse));
        } else {
          this._status.set({
            state: 'unreachable',
            message: 'The API is not reachable. Is api-service running on port 8080?',
          });
        }
      },
    });
  }
}

function toStatus(health: HealthResponse): SystemStatus {
  const components = Object.entries(health.components ?? {}).map(([name, c]) => ({
    name,
    status: c.status,
  }));
  return { state: health.status === 'UP' ? 'up' : 'down', overall: health.status, components };
}
