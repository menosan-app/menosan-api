# menosan-api

Kotlin + Ktor backend for **Menosan**, a household waste-prevention app for Dumaguete City.

- Development plan: [`docs/DEVELOPMENT_PLAN.md`](docs/DEVELOPMENT_PLAN.md)
- Agent rules: [`AGENTS.md`](AGENTS.md) · Handoff log: [`docs/HANDOFF.md`](docs/HANDOFF.md)
- API contract: [`docs/api-contract.md`](docs/api-contract.md) · [`docs/CHANGELOG-contract.md`](docs/CHANGELOG-contract.md)
- Decisions: [`docs/DECISIONS.md`](docs/DECISIONS.md)
- Environments, deployment, staging dev tools, e2e: [`docs/ENVIRONMENTS.md`](docs/ENVIRONMENTS.md)
- Taxonomy (source of truth): `src/main/resources/taxonomy.json`

## Local setup
1. A JDK (17+) to run Gradle. The build compiles for **JDK 21** and downloads it automatically if missing (foojay toolchain resolver). On Windows without a JDK on `PATH`, Android Studio's bundled one works:
   `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"` (PowerShell).
2. Copy `.env.example` to `.env` and fill in the values. Never commit `.env`.
3. `./gradlew test` needs no secrets (Firebase and Gemini are faked, Postgres is embedded).
4. `./gradlew run` applies Flyway migrations to `DATABASE_URL_DIRECT` and serves on `PORT` (default 8080).

## Build
- `./gradlew buildFatJar` → `build/libs/menosan-api.jar`
- `docker build -t menosan-api .` (all config from env vars; the image defaults to `APP_ENV=prod`)
