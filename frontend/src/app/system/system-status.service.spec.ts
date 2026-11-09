import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { SystemStatusService } from './system-status.service';

describe('SystemStatusService', () => {
  let service: SystemStatusService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(SystemStatusService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reports UP with components', () => {
    service.refresh();
    expect(service.status().state).toBe('loading');

    http.expectOne('/actuator/health').flush({
      status: 'UP',
      components: { db: { status: 'UP' }, diskSpace: { status: 'UP' } },
    });

    expect(service.isUp()).toBe(true);
    expect(service.status()).toEqual({
      state: 'up',
      overall: 'UP',
      components: [
        { name: 'db', status: 'UP' },
        { name: 'diskSpace', status: 'UP' },
      ],
    });
  });

  it('treats a 503 health body as DOWN, not unreachable', () => {
    service.refresh();
    http
      .expectOne('/actuator/health')
      .flush({ status: 'DOWN', components: { db: { status: 'DOWN' } } }, { status: 503, statusText: 'Service Unavailable' });

    expect(service.status().state).toBe('down');
    expect(service.isUp()).toBe(false);
  });

  it('reports unreachable on network errors', () => {
    service.refresh();
    http.expectOne('/actuator/health').error(new ProgressEvent('error'), { status: 0 });

    expect(service.status().state).toBe('unreachable');
  });
});
