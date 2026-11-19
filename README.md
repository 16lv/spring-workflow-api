# spring-workflow-api

Stateless REST API for engineering project and task lifecycle tracking built with **Java 21**, **Spring Boot 3.3**, **Spring Security** (HMAC-SHA256 JWT), **Spring Data JPA**, PostgreSQL/H2, and **OpenAPI / Swagger UI**.

## Overview

`spring-workflow-api` exposes authenticated endpoints for managing engineering projects and workflow tasks, enforcing valid state transitions inside the domain model and mapping errors cleanly onto RFC 7807 `ProblemDetail` payloads.

## Features

- **Stateless JWT authentication**: `/api/v1/auth/register` and `/api/v1/auth/login` hash passwords with BCrypt and issue HS256 JWT tokens validated per request via `JwtAuthenticationFilter`. Public sign-up always assigns `Role.MEMBER`.
- **Enforced workflow state machine**: `TaskStatus` validates transitions (`BACKLOG -> IN_PROGRESS -> IN_REVIEW -> DONE`, plus `ARCHIVED`), returning HTTP `422` (`ProblemDetail`) when invalid state jumps are requested.
- **Query efficiency (`@EntityGraph`)**: Repository queries fetch `project` and `assignee`/`owner` relations eagerly when serialising responses, avoiding N+1 lazy lookups while returning an explicit `PagedTaskResponse` DTO.
- **Structured error responses**: `GlobalExceptionHandler` converts Bean Validation, missing entity (`404`), conflict (`409`), and workflow state errors (`422`) into RFC 7807 JSON payloads.

## Technology Stack

- Java 21 LTS
- Spring Boot 3.3.5 (Web, Security, Validation, Data JPA)
- `jjwt` 0.12.6 (HMAC-SHA256)
- PostgreSQL 16 (Docker Compose) & H2 (PostgreSQL compatibility mode for default tests)
- Springdoc OpenAPI / Swagger UI

## Endpoints

| Method | Path | Access | Purpose |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/v1/health` | Public | Service status check |
| `POST` | `/api/v1/auth/register` | Public | Register `MEMBER` account and issue JWT |
| `POST` | `/api/v1/auth/login` | Public | Authenticate credentials |
| `GET` / `POST` | `/api/v1/projects` | JWT | List projects or create a project |
| `GET` | `/api/v1/projects/{code}` | JWT | Fetch project summary by code |
| `GET` / `POST` | `/api/v1/tasks` | JWT | Filter paginated tasks or create a task |
| `GET` | `/api/v1/tasks/{id}` | JWT | Fetch single task by ID |
| `PATCH` | `/api/v1/tasks/{id}` | JWT | Update task metadata |
| `PATCH` | `/api/v1/tasks/{id}/status` | JWT | Transition task workflow state |
| `DELETE` | `/api/v1/tasks/{id}` | JWT | Delete task (`204`) |

## Usage Examples

Run the local integration suite on H2:

```bash
mvn -B test
```

Start PostgreSQL 16 + API container with Docker Compose:

```bash
docker compose up --build
```

Register a user and inspect Swagger UI at `http://localhost:8080/swagger-ui.html`:

```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"engineer@example.org","displayName":"Jordan","password":"Password123!"}'
```

## Project Structure

```text
spring-workflow-api/
├── src/main/java/agency/shss7/taskflow/
│   ├── controller/
│   ├── domain/
│   ├── dto/
│   ├── repository/
│   ├── security/
│   └── service/
├── src/main/resources/application.yml
├── src/test/java/agency/shss7/taskflow/
├── Dockerfile
├── docker-compose.yml
└── pom.xml
```

## Known Limitations & Future Improvements

- Default local configuration uses JPA `ddl-auto`; adding Flyway versioned SQL migrations is the next step for production schema evolution.
- Access tokens are short-lived bearer JWTs with no server-side revocation or refresh-token rotation table yet.
