# 0001. Platform versions: Spring Boot 4.1 + Spring AI 2.0

- **Status:** Accepted
- **Date:** 2026-11-09

## Context

The RAG features depend on Spring AI, so Spring AI decides which Spring Boot versions we can use.
Choosing a Boot version that Spring AI doesn't officially support leads to subtle auto-configuration
and dependency conflicts.

On 2026-11-09, Spring Initializr's compatibility metadata (`start.spring.io/actuator/info`, `bom-ranges`) listed:

| Spring AI | Supported Spring Boot range |
|-----------|-----------------------------|
| 2.0.1 (latest GA) | `>= 4.0.0 and < 4.2.0-M1` |
| 2.1.0-M1 (milestone) | `>= 4.2.0-M1` |

At the same time, Spring Boot **4.1.2** was the newest GA release (4.2.0 was still at RC).
Spring Boot 3.5 reached end of open-source support in June 2026.

## Decision

- **Spring Boot 4.1.2**: the latest stable Boot within Spring AI 2.0.x's supported range.
- **Spring AI 2.0.1**: the latest GA release. It's imported as a BOM (`spring-ai-bom`) in both services,
  so every Spring AI module gets the same version.
- **Java 21** (LTS), built with the Maven wrapper that Initializr generates.
- Container images and GitHub Actions are pinned to exact versions or major tags. CI runs on
  `ubuntu-24.04`, never `ubuntu-latest`.

## Consequences

- We use Boot 4 conventions: modular starters (`spring-boot-starter-webmvc`, `spring-boot-starter-flyway`),
  Testcontainers 2 (`org.testcontainers.postgresql.PostgreSQLContainer`), and Jakarta EE 11.
- We can't move to Boot 4.2 until we also move to Spring AI 2.1 GA. That's one coordinated upgrade,
  not two independent ones.
- Some third-party libraries may still lag behind Boot 4. If one blocks us, we look for a replacement
  rather than downgrading the platform.
