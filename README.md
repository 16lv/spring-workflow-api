# spring-workflow-api

Stateless REST API for engineering project and task lifecycle tracking built with **Java 21**, **Spring Boot 3.3**, **Spring Security** (HMAC-SHA256 JWT), **Spring Data JPA**, and **OpenAPI / Swagger UI**.

## Design & Behaviour

- **Authentication**: `/api/v1/auth/register` and `/api/v1/auth/login` hash passwords with BCrypt and issue signed HS256 JWT tokens validated per request via `JwtAuthenticationFilter`.
- **Enforced workflow transitions**: `TaskStatus` checks allowed transitions in the domain entity (`BACKLOG -> IN_PROGRESS -> IN_REVIEW -> DONE`, plus `ARCHIVED`), returning RFC 7807 `ProblemDetail` (`422 Unprocessable Entity`) when a caller attempts to skip steps.
- **Query & count efficiency**: Project summaries compute total and completed task counts using repository `COUNT` queries instead of triggering lazy `OneToMany` collection loads, and `GET /api/v1/tasks` supports multi-predicate paginated filtering.

## Endpoints

| Method | Path | Access | Purpose |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/v1/health` | Public | Service status check |
| `POST` | `/api/v1/auth/register` | Public | Register account and issue JWT |
| `POST` | `/api/v1/auth/login` | Public | Authenticate credentials |
| `GET` / `POST` | `/api/v1/projects` | JWT | List projects or create a project |
| `GET` | `/api/v1/projects/{code}` | JWT | Fetch project summary by code |
| `GET` / `POST` | `/api/v1/tasks` | JWT | Filter paginated tasks or create a task |
| `PATCH` | `/api/v1/tasks/{id}` | JWT | Update task metadata |
| `PATCH` | `/api/v1/tasks/{id}/status` | JWT | Transition task workflow state |
| `DELETE` | `/api/v1/tasks/{id}` | JWT | Delete task (`204`) |

## Build & Test

Run with the default H2 PostgreSQL-compatibility datasource:

```bash
mvn test
```

Run the full Docker Compose stack (PostgreSQL 16 + API container):

```bash
docker compose up --build
```
