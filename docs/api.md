# API

The live, authoritative reference is generated from the code: Swagger UI at
`http://localhost:8080/swagger-ui.html`, OpenAPI document at `/v3/api-docs`. This page records the
conventions every endpoint follows and lists what is implemented.

## Conventions

- Base path `/api`. Requests and responses are JSON. Unknown request properties are rejected.
- Timestamps are ISO-8601 UTC (`2026-03-02T04:30:00Z`). Durations are integer seconds, with the unit in the
  field name (`setupSeconds`).
- Identifiers are UUIDs.
- A single resource is returned as the object. Creation answers `201` with a `Location` header. Simulation
  submission answers `202`.

### Lists

```json
{ "items": [], "page": 0, "size": 50, "totalItems": 0, "totalPages": 0 }
```

`page` is zero-based. `size` defaults to 50 and is capped at 200.

### Errors

Every error is an RFC 9457 problem document with content type `application/problem+json`:

```json
{
  "type": "about:blank",
  "title": "Validation failed",
  "status": 400,
  "detail": "One or more fields are invalid.",
  "instance": "/api/resources",
  "errors": [{ "field": "capacity", "message": "must be greater than or equal to 0" }]
}
```

`errors` is present only for validation failures and is sorted by field. Responses never contain stack
traces, exception class names or database messages.

| Status | Meaning |
|---|---|
| 400 | Malformed JSON, or a field failed validation |
| 404 | No such resource or path |
| 409 | Conflict: duplicate, or the record changed since it was read |
| 422 | Valid request that a business rule or state transition forbids |
| 429 | Simulation queue is full; retry later |
| 500 | Unexpected failure; details are in the server log only |

### CORS

Browser calls to `/api/**` are accepted only from the origins in `CORS_ALLOWED_ORIGINS`.

## Implemented endpoints

### `GET /api/system/info`

What the backend is connected to. If the database is unreachable the call still answers `200`, with
`database.status` `"DOWN"` and null product and version.

```json
{
  "application": "RealityCheck",
  "version": "0.1.0",
  "serverTime": "2026-11-14T05:54:59Z",
  "database": { "status": "UP", "product": "PostgreSQL", "version": "17.2" },
  "migrations": { "appliedCount": 1, "currentVersion": "1" },
  "ai": { "enabled": false, "provider": "none" }
}
```

### Actuator

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | Overall status, no component details |
| `GET /actuator/health/liveness` | Process is alive |
| `GET /actuator/health/readiness` | Ready to serve; used by the container health check |
| `GET /actuator/info` | Build information |

No other actuator endpoint is exposed.

## Planned endpoints

Not implemented yet. Shapes are specified in `.kiro/specs/`; the table shows where each group lands.

| Group | Endpoints | Milestone |
|---|---|---|
| Factory | `GET/POST /api/factories`, `GET /api/factories/{id}`, `GET /api/factories/{id}/state?at=` | 2, 5 |
| Workflow | `GET/POST /api/workflows`, `GET /api/workflows/{id}`, `GET /api/workflows/{id}/stages` | 2 |
| Resources | `GET/POST /api/resources`, `PUT /api/resources/{id}`, `GET /api/resources/{id}/availability` | 2 |
| Production | `GET/POST /api/orders`, `GET /api/orders/{id}`, `GET/POST /api/events`, `GET /api/timeline` | 3 |
| Scenarios | `GET/POST /api/scenarios`, `GET/PATCH /api/scenarios/{id}`, `POST /api/scenarios/{id}/changes`, `PUT/DELETE /api/scenarios/{id}/changes/{changeId}` | 5 |
| Simulation | `POST /api/scenarios/{id}/simulate`, `GET /api/scenarios/{id}/simulations`, `GET /api/simulations/{runId}`, `.../results`, `.../stages` | 4, 5 |
| Comparison | `POST /api/simulations/compare`, `GET /api/scenarios/{id}/comparisons` | 5 |
| AI | `POST /api/ai/insights`, `POST /api/ai/ask`, `GET /api/ai/insights/{runId}` | 7 |
| Demo | `POST /api/demo/reset` (only when enabled by configuration) | 2 |
