# Repository Guidelines

## Project Structure & Module Organization
- `src/main/java/`: Java sources (entrypoint: `goat.thaw.Thaw`).
- `src/main/resources/`: assets and `plugin.yml` (kept in sync with POM version).
- `pom.xml`: Maven config (Java 21, shade plugin).
- `target/`: build outputs (final jar: `Thaw-1.0-SNAPSHOT.jar`).

## Build, Test, and Development Commands
- Build: `mvn clean package` — compiles and produces a shaded jar in `target/`.
- Fast build: `mvn -DskipTests package` — skip tests (none by default).
- Clean: `mvn clean` — remove `target/`.
- Run in server: copy `target/Thaw-1.0-SNAPSHOT.jar` to your Paper/Spigot `plugins/` dir, then start the server.

## Coding Style & Naming Conventions
- Indentation: 4 spaces; UTF-8 encoding.
- Java 21 features allowed; avoid preview unless justified.
- Packages: lowercase (`goatl.thaw`); classes: `PascalCase`; methods/fields: `camelCase`.
- Bukkit listeners end with `Listener` (e.g., `FreezeListener`); commands with clear verbs (e.g., `ThawCommand`).
- Keep `plugin.yml` names consistent with class/package and version.

## Testing Guidelines
- Location: `src/test/java/`.
- Framework: prefer JUnit 5 if/when added to `pom.xml`.
- Naming: mirror class names with `*Test` (e.g., `ThawTest`).
- Run: `mvn test` (once tests exist). Aim for meaningful unit coverage of utility and logic classes.

## Commit & Pull Request Guidelines
- Commits: imperative, concise subject (≤72 chars). Example: `feat: add freeze scheduler`.
- Conventional Commits encouraged (`feat`, `fix`, `refactor`, `chore`, `test`, `docs`).
- PRs: include summary, rationale, screenshots/logs if runtime behavior changes, and testing notes. Link related issues.

## Security & Configuration Tips
- Target API: `api-version: '1.21'` (see `plugin.yml`).
- Dependencies are shaded; keep `scope` `provided` for server APIs.
- Avoid NMS unless necessary; if used, isolate behind interfaces.
- Do not block the main server thread; use schedulers for long tasks.

## Architecture Overview
- Main plugin: `Thaw extends JavaPlugin` with `onEnable/onDisable` hooks.
- Register listeners/commands in `onEnable()` and clean up in `onDisable()`.
- Prefer small, focused services (e.g., `FreezeService`, `TemperatureService`) wired from the main class.
