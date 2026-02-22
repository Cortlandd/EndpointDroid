# EndpointDroid

EndpointDroid is a JetBrains plugin for **Android Studio** that discovers API endpoints in your Android codebase, shows them in a dedicated Tool Window, and (next) exports runnable requests for JetBrains HTTP Client.

## Project Goals

1. Make API surface area visible without leaving the IDE.
2. Let developers inspect endpoint details quickly (method, path, types, URL).
3. Produce runnable `.http` requests and env files with minimal setup.
4. Grow from Retrofit-first scanning into a pluggable provider architecture (Ktor, OkHttp, Volley).

## What It Looks Like

The `EndpointDroid` Tool Window is split into two panes:

- Left pane: discovered endpoint list.
- Right pane: endpoint details/docs view.

Current flow:

1. Open your Android project.
2. Open the `EndpointDroid` Tool Window.
3. Click `Refresh`.
4. Endpoints appear in the list; selecting one shows details.

Example details panel output:

```text
# GET /api/v1/films

- Service: com.example.network.SotwApi
- Function: films
- URL: https://api.example.com/api/v1/films

## Types
- Request: None
- Response: Object
```

If no base URL is resolved yet, URL uses `{{host}}` as placeholder.

## Current Implementation (Step Status)

Completed:

- Step A: no scan on tool window init (explicit refresh only).
- Step B: refresh is dumb-mode safe via `runWhenSmart`; UI updates on EDT.
- Step C: scanner results are wired through `EndpointService` into the list.
- Step 4 (initial slice): base URL inference + `endpointdroid.yaml` override.

In progress next:

- Step 5: export selected/all endpoints to JetBrains HTTP Client files.

## Endpoint Discovery (Current)

Provider support today:

- Retrofit annotations on interface methods.

Supported Retrofit HTTP annotations:

- `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, `OPTIONS`, `HTTP`

Extracted fields (current):

- `httpMethod`
- `path`
- `serviceFqn`
- `functionName`
- `requestType` (first `@Body` parameter, best-effort)
- `responseType` (best-effort; unwraps `Call<T>` and `Response<T>`)
- `baseUrl` (global best-effort value for now)

Notes:

- Kotlin suspend return type accuracy is still limited in v0 without Kotlin plugin dependency.
- Parameter extraction for `@Path`, `@Query`, headers, multipart is planned (Step 6).

## Base URL Handling

EndpointDroid resolves a project-level base URL in this order:

1. Config override from project root `endpointdroid.yaml`
2. Source heuristics from code
3. Fallback to `{{host}}` placeholder

### Config Override (Supported Now)

File location:

- `<your-android-project>/endpointdroid.yaml`

Supported keys:

- `baseUrl`
- `base_url`

Example:

```yaml
baseUrl: https://api.example.com
```

Rules:

- Config wins over heuristics.
- Value must start with `http://` or `https://`.
- Trailing `/` is trimmed for stable URL joining.
- Inline comments (`# ...`) are ignored.

### Heuristics (Supported Now)

If config is missing, EndpointDroid scans project source for:

- `Retrofit.Builder().baseUrl("...")`
- `baseUrl(SOME_CONSTANT)` where `SOME_CONSTANT` maps to a simple Kotlin/Java string constant

### Planned (Not Yet Implemented)

- `.endpointdroid.yaml` alternate filename
- Environment map (`dev`/`stage`/`prod`) + default environment
- Per-service base URL mapping (`serviceFqn -> env/host`)

## Constraints and Safety Rules

- No index-dependent scan work during dumb mode.
- UI updates happen on EDT.
- Scanning failures must not crash tool window initialization.
- Keep commits small and reversible.
- Add KDoc for new public APIs.

## Roadmap (Planned)

- Step 5: export `.http` + `http-client.env.json`.
- Step 6: richer docs renderer and parameter extraction.
- Step 7: background scan performance/caching improvements.
- Step 8: Kotlin/K2 accuracy and dependency reintroduction.
- Step 9: `EndpointProvider` architecture.
- Step 10: additional providers.
- Step 11: Postman/Insomnia importers.
- Step 12: UX polish, settings, and tests.

## Development

Build:

```bash
./gradlew build
```

Run plugin sandbox:

```bash
./gradlew runIde
```
