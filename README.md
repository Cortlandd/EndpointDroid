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
- Left pane source tabs: `EndpointDroid`, `Postman`, `Insomnia` (selection is persisted per project).
- `Postman` tab supports direct collection JSON import from the tab-local import button.

Current flow:

1. Open your Android project.
2. Open the `EndpointDroid` Tool Window.
3. Click `Refresh` to scan.
4. Click `Config` to open/create `endpointdroid.yaml` overrides.
5. Endpoints appear in the list; selecting one shows details.

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

- Step 5: improve endpoint presentation (left list UX + richer markdown details).
- Step 6: continue docs/list quality and extraction details.
- Step 7: make scanning fast + non-blocking.
- Step 8: improve Kotlin-first scanning accuracy.
- Step 9: export selected/all endpoints to JetBrains HTTP Client files.

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
- source location (`file:line`, best-effort)

Notes:

- Kotlin suspend return type accuracy is still limited in v0 without Kotlin plugin dependency.
- Parameter extraction is best-effort and currently supports Retrofit method params and header hints.

## Retrofit Params and Headers (Current)

EndpointDroid currently inspects Retrofit parameter annotations and reflects them in the docs panel and HTTP draft.

Supported parameter annotations:

- `@Path` (and `@Param` fallback)
- `@Query`, `@QueryMap`
- `@Header`, `@HeaderMap`
- `@Field`, `@FieldMap`
- `@Part`, `@PartMap`
- `@Body`
- `@Url`

Dynamic header example:

```java
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Header;

public interface ApiService {
    @GET("user/profile")
    Call<YourDataType> getUserProfile(@Header("Authorization") String token);
}
```

How EndpointDroid handles this:

- Shows `Authorization` under header parameters for that endpoint.
- Adds an Authorization line in HTTP draft when auth is inferred as required.
- Does not force Authorization for endpoints where no auth hint is found.
- Uses optional auth hint when only `@HeaderMap` is present.

## Base URL Handling

EndpointDroid resolves a project-level base URL in this order:

1. Config override from project root `endpointdroid.yaml`
2. Source heuristics from code
3. Fallback to `{{host}}` placeholder

### Config Override (Supported Now)

File location:

- `<your-android-project>/endpointdroid.yaml`
- `<your-android-project>/.endpointdroid.yaml`

Supported scalar keys:

- `baseUrl`
- `base_url`
- `defaultEnv`
- `default_env`

Supported map sections:

- `environments`
- `serviceBaseUrls`
- `servicePaths`
- `serviceRequestTypes`
- `serviceResponseTypes`

Map keys can target:

- Service-level override: `com.example.api.SotwApi`
- Endpoint-level override: `com.example.api.SotwApi#search`

Example:

```yaml
baseUrl: https://api.example.com
# defaultEnv: dev

environments:
  dev: https://dev.api.example.com
  stage: https://stage.api.example.com
  prod: https://api.example.com

serviceBaseUrls:
  com.example.api.SotwApi: prod
  com.example.api.AuthApi#login: https://auth.api.example.com

servicePaths:
  com.example.api.AuthApi#login: /v1/login

serviceRequestTypes:
  com.example.api.AuthApi#login: LoginRequest

serviceResponseTypes:
  com.example.api.AuthApi#login: TokenResponse
```

Rules:

- Config wins over scanner heuristics.
- `service*` endpoint-level (`Service#function`) keys win over service-level keys.
- Value must start with `http://` or `https://`.
- Trailing `/` is trimmed for stable URL joining.
- Inline comments (`# ...`) are ignored.
- `serviceBaseUrls` values can be an absolute URL or an `environments` key.
- If `servicePaths` is absolute (`https://...`), EndpointDroid derives both path and base URL.

### Heuristics (Supported Now)

If config is missing, EndpointDroid scans project source for:

- `Retrofit.Builder().baseUrl("...")`
- `baseUrl(SOME_CONSTANT)` where `SOME_CONSTANT` maps to a simple Kotlin/Java string constant

### Planned (Not Yet Implemented)

- Dedicated settings UI for editing config in-IDE.
- Validation diagnostics for malformed override keys.
- Export config assistant for generated `.http` environments.

## Constraints and Safety Rules

- No index-dependent scan work during dumb mode.
- UI updates happen on EDT.
- Scanning failures must not crash tool window initialization.
- Keep commits small and reversible.
- Add KDoc for new public APIs.

## Roadmap (Planned)

- Step 5: richer endpoint list/docs presentation and parameter extraction.
- Step 6: docs/list refinement and UX cleanup.
- Step 7: background scan performance/caching improvements.
- Step 8: Kotlin/K2 accuracy and dependency reintroduction.
- Step 9: export `.http` + `http-client.env.json`.
- Step 10: `EndpointProvider` architecture.
- Step 11: additional providers.
- Step 12: Postman/Insomnia importers.
- Step 13: UX polish, settings, and tests.
- Step 14: request stubbing architecture and generation workflow.

## Development

Build:

```bash
./gradlew build
```

Run plugin sandbox:

```bash
./gradlew runIde
```
