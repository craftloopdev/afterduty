# After Duty — Spring Boot

AI-powered VA disability claim analysis. Gemini extraction + Claude synthesis.

Built with Spring Boot 3.4, Java 17, Spring AI, Spring Data JPA.

## Prerequisites

- Java 17+ (Temurin recommended)
- Gradle 8.x (wrapper included)

## Local Development

```bash
# Run with H2 database and dev-mode auth
./gradlew bootRun --args='--spring.profiles.active=local'

# API is at http://localhost:8080
# H2 console at http://localhost:8080/h2-console (JDBC URL: jdbc:h2:file:./data/vaclaim)
```

## Running Tests

```bash
./gradlew test
```

## Building

```bash
./gradlew bootJar
# JAR at build/libs/after-duty-4.0.0.jar
```

## Deploy to Cloud Run

```bash
gcloud run deploy va-claim-api --source . --region us-central1 \
  --project <your-project> --allow-unauthenticated \
  --set-env-vars DEV_MODE=true,GCP_PROJECT=<your-project>
```

## Environment Variables

| Variable | Description | Default |
|---|---|---|
| `DEV_MODE` | Accept X-User-Email header for testing | `true` |
| `GCP_PROJECT` | GCP project for Vertex AI (Gemini + Claude) | `<your-project>` |
| `PORT` | Server port (Cloud Run sets this) | `8080` |
| `CLOUD_SQL_INSTANCE` | Cloud SQL instance connection name | - |
| `DB_NAME` | PostgreSQL database name | `vaclaim` |
| `DB_USER` | PostgreSQL username | `vaclaim` |
| `DB_PASSWORD` | PostgreSQL password | - |

## API Endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/health` | Health check |
| `GET` | `/api/auth/me` | Current user |
| `POST` | `/api/auth/profile` | Create/update service profile |
| `GET` | `/api/auth/profile` | Get service profile |
| `POST` | `/api/intake/sessions` | Create intake session |
| `GET` | `/api/intake/sessions` | List sessions |
| `POST` | `/api/intake/sessions/{id}/upload` | Upload evidence |
| `POST` | `/api/intake/sessions/{id}/chat` | Chat message |
| `POST` | `/api/intake/sessions/{id}/quick-add` | Quick add evidence |
| `POST` | `/api/intake/sessions/{id}/analyze` | Trigger analysis |
| `GET` | `/api/intake/sessions/{id}/conditions` | Get conditions |
| `GET` | `/api/intake/sessions/{id}/evidence` | Get evidence |
| `GET` | `/api/intake/sessions/{id}/messages` | Get messages |
| `GET` | `/api/intake/sessions/{id}/pipeline-status` | Pipeline status |
| `GET` | `/api/vasrd/search?q=` | Search VASRD codes |
| `GET` | `/api/vasrd/codes/{code}` | Get VASRD code |
| `POST` | `/api/scenarios` | Create scenario |
| `GET` | `/api/scenarios` | List scenarios |
| `POST` | `/api/scenarios/calculate` | Quick VA math |
