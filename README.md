# EndpointDroid

EndpointDroid is an Android Studio plugin that discovers API endpoints (currently Retrofit-first), shows them in a Tool Window, and prepares export to JetBrains HTTP Client files.

## Current Status

- Step A complete: tool window no longer auto-scans on init.
- Step B complete: Refresh is dumb-mode safe (`runWhenSmart`) and updates UI on EDT.
- Step C complete: scanner results populate the endpoint list through `EndpointService`.
- Step 4 (initial slice) complete: base URL is resolved via config override or best-effort source inference.

## Supported IDE Target

- Android Studio Otter 3 Feature Drop (2025.2.3)
- IntelliJ platform build 252.x
- Runtime JBR 21

## How Scanning Works (Current)

1. Open your Android project in Android Studio.
2. Open the `EndpointDroid` tool window.
3. Click `Refresh`.
4. EndpointDroid waits until smart mode if indexing is active.
5. Retrofit endpoints are scanned and listed.

## Config Files

EndpointDroid reads optional config from the project you are scanning (not the plugin repo).

### `endpointdroid.yaml` (supported now)

Location:

- Place at the root of the Android project you opened in the IDE.

Supported keys:

- `baseUrl`
- `base_url`

Example:

```yaml
# /path/to/your/android-project/endpointdroid.yaml
baseUrl: https://api.example.com
```

Behavior:

- Config value takes precedence over heuristic inference.
- Value must start with `http://` or `https://`.
- Trailing `/` is trimmed for consistent URL joining.
- Inline comments using `#` are ignored.

If config is missing, EndpointDroid falls back to source heuristics:

- `Retrofit.Builder().baseUrl("...")`
- `baseUrl(SOME_CONSTANT)` where `SOME_CONSTANT` resolves to a simple Kotlin/Java string constant

If no base URL is found, endpoint docs use `{{host}}` placeholder.

### Not Implemented Yet (Planned)

- `.endpointdroid.yaml` alternate config filename
- Environment blocks (`dev/stage/prod`) and default environment selection
- Per-service base URL mapping (`serviceFqn -> env/host`)

## Constraints We Keep

- No index-based scanning during dumb mode.
- UI mutations stay on EDT.
- Keep commits small and reversible.
- Public APIs should include KDoc.

## Roadmap (Planned)

- Step 5: export selected/all endpoints to `.http` and `http-client.env.json`.
- Step 6: richer docs rendering and parameter extraction.
- Step 7: background scanning and caching improvements.
- Step 8: Kotlin/K2 accuracy improvements and dependency reintroduction.
- Step 9: provider architecture (`EndpointProvider`).
- Step 10: additional providers (Ktor, OkHttp, Volley).
- Step 11: importers (Postman/Insomnia).
- Step 12: product polish, settings, and tests.

## Local Development

Build:

```bash
./gradlew build
```

Run plugin in IDE sandbox:

```bash
./gradlew runIde
```
