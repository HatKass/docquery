/**
 * Fails fast, with a readable message, when part of the stack is not running (instead of every test
 * timing out on a login page or an upload that is never processed).
 */
export default async function globalSetup(): Promise<void> {
  const baseURL = process.env['E2E_BASE_URL'] ?? 'http://localhost:4200';
  const keycloak = process.env['E2E_KEYCLOAK_URL'] ?? 'http://localhost:8180/realms/docquery';
  const worker = process.env['E2E_WORKER_HEALTH_URL'] ?? 'http://localhost:8081/actuator/health';
  const checks: [string, string, string][] = [
    ['Angular app', baseURL, 'cd frontend && npm start'],
    ['api-service (through the dev proxy)', `${baseURL}/actuator/health`, 'cd api-service && ./mvnw spring-boot:run'],
    ['ingestion-worker', worker, 'cd ingestion-worker && ./mvnw spring-boot:run'],
    ['Keycloak realm', `${keycloak}/.well-known/openid-configuration`, 'docker compose up -d'],
  ];
  const problems: string[] = [];
  for (const [name, url, hint] of checks) {
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(10_000) });
      if (!response.ok) {
        problems.push(`${name}: ${url} answered ${response.status} (start it with: ${hint})`);
      }
    } catch {
      problems.push(`${name}: ${url} is not reachable (start it with: ${hint})`);
    }
  }
  if (problems.length) {
    throw new Error(`The e2e tests need the whole stack running (see e2e/README.md):\n- ${problems.join('\n- ')}`);
  }
}
