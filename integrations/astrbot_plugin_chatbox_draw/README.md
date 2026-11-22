# Chatbox Draw

Chatbox Draw is an optional **AstrBot server plugin** (AstrBot 4.29.4+). The Chatbox Windows MSI does not install or update it. The desktop client can display images sent by AstrBot; image generation is performed by this plugin on the server.

Use `/画图 <画面描述>` or explicitly ask the assistant to create a new image. Sticker requests remain with the sticker plugin. Each accepted event makes at most one paid generation attempt and is scoped to one enrolled WebChat administrator conversation.

## Source and provenance

The complete deployable source is this directory: `main.py`, `_conf_schema.json`, and `metadata.yaml`. The original compatible-mode implementation is preserved on this machine in `%LOCALAPPDATA%\Chatbox\draw-deployment-backups\1795339683565242900\main.py`; its SHA-256 is `3B151B3E8ED394EF6150732370D93AF3EB7AD8E46C474C9E757115923130F02D`. The existing native implementation with SHA-256 `A11EA096E9CA6BB277AB542A2F58B2D7615C3650B76EF90422A8CE72690FD8FA` was a real deployed intermediate. The current canonical `main.py` SHA-256 is `F574A45A3EC04AA5BAF0F5E685C3BDB0A85641DED593E72998893A44E229DE1E`; the live plugin file was verified to match it.

`../patches/astrbot_plugin_chatbox_draw-3B151B3E-to-native.patch` records the source-only change from the preserved original `main.py` to the repository's native implementation. Its paths are relative to the plugin directory. The patch does not contain plugin settings or credentials. Its SHA-256 is `163106CBE4C1A3C41648F705C252FDF11EEB8A200ABB01D51EE5A8064A71FFD5`. The following PowerShell commands replay it in a scratch directory and verify the resulting source hash. Disabling autocrlf preserves the source's mixed line endings in that standalone scratch directory. The repository attributes also preserve the source bytes and LF patch bytes through a fresh checkout with `core.autocrlf=true`; this was verified by applying the patch from a fresh scratch clone and checking the resulting source hash.

```powershell
# Run from the repository root. This only writes under the system temp directory.
$base = Join-Path $env:LOCALAPPDATA 'Chatbox\draw-deployment-backups\1795339683565242900\main.py'
$patch = (Resolve-Path .\integrations\patches\astrbot_plugin_chatbox_draw-3B151B3E-to-native.patch).Path
$scratch = Join-Path $env:TEMP ('draw-patch-check-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $scratch | Out-Null
Copy-Item -LiteralPath $base -Destination (Join-Path $scratch 'main.py')
git -C $scratch init -q
if ($LASTEXITCODE -ne 0) { throw 'Could not initialize patch scratch repository.' }
git -c core.autocrlf=false -C $scratch apply --check $patch
if ($LASTEXITCODE -ne 0) { throw 'Patch does not apply cleanly to the original.' }
git -c core.autocrlf=false -C $scratch apply $patch
if ($LASTEXITCODE -ne 0) { throw 'Patch application failed.' }
(Get-FileHash (Join-Path $scratch 'main.py') -Algorithm SHA256).Hash
# Expected: F574A45A3EC04AA5BAF0F5E685C3BDB0A85641DED593E72998893A44E229DE1E
```

For a new checkout, deploy the complete plugin source below; the patch is for provenance and reconstruction from the original local backup, not a substitute for the complete source.

## Configuration route

Configure the plugin in the running AstrBot WebUI's plugin management page. Its two fields are defined in `_conf_schema.json`:

| Field | Value |
| --- | --- |
| `provider_id` | ID of an existing `openai_responses` provider configured for a supported HTTPS DashScope base URL |
| `allowed_origin` | Exact UMO of the one WebChat conversation to enroll |

Both values default to empty; generation fails closed until both are set. Keep the actual provider ID and UMO in the private AstrBot configuration, not in this repository. The plugin reads the provider's currently selected key; do not add another key to plugin settings or source. The schema and tests confirm this configuration route and its empty defaults without reading a live instance configuration.

The plugin calls DashScope's native synchronous `/api/v1/services/aigc/multimodal-generation/generation` endpoint for `qwen-image-3.0`, requesting one 1024×1024 PNG. Only an AstrBot administrator event with the exact configured UMO and matching embedded WebChat sender is authorized. PNG data URLs are validated locally. Temporary result URLs are fetched only from the accepted DashScope OSS result hosts, without redirects or provider credentials, with a 20 MB and 20-second bound. A completed native request emits a sanitized receipt to AstrBot logs with the model, elapsed time, and a validated UUID request ID when provided; it does not log the prompt, key, or image payload.

## Focused offline verification

Run the focused tests with the **installed AstrBot virtual environment's Python**. The test file sets a temporary `ASTRBOT_ROOT`, uses synthetic provider/events and mocks downloads; it does not call the image service or consume a provider key. Run from a fresh temporary working directory, and use `-B` to prevent bytecode files:

```powershell
# Start in the repository root, with the installed AstrBot venv activated.
# Set this to that instance's core source root (the directory containing astrbot\).
$coreRoot = '<AstrBot core source root>'
$test = (Resolve-Path .\integrations\tests\test_chatbox_draw.py).Path
$python = (Get-Command python).Source
$work = Join-Path $env:TEMP ('chatbox-draw-test-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $work | Out-Null
$previousPythonPath = $env:PYTHONPATH
if ($previousPythonPath) { $env:PYTHONPATH = "$coreRoot;$previousPythonPath" }
else { $env:PYTHONPATH = $coreRoot }
Push-Location $work
try {
    & $python -B $test -v
    if ($LASTEXITCODE -ne 0) { throw "Chatbox Draw tests failed ($LASTEXITCODE)." }
}
finally {
    Pop-Location
    $env:PYTHONPATH = $previousPythonPath
}
```

## Deploy and reload

1. Set `$dataDir` to the data directory used by this AstrBot instance. The exact target is `$dataDir\plugins\astrbot_plugin_chatbox_draw`.
2. Before replacing any files, make a timestamped copy of the entire target plugin directory under `%LOCALAPPDATA%\Chatbox\draw-deployment-backups\<timestamp>\astrbot_plugin_chatbox_draw\`. Keep this backup intact and record SHA-256 hashes of the files copied. The timestamped backup is the rollback source; do not overwrite it during later deployments.
3. Copy `main.py`, `_conf_schema.json`, and `metadata.yaml` from this repository's `integrations\astrbot_plugin_chatbox_draw` directory into the target. Keep the runtime target limited to the plugin files; do not copy documentation, `__pycache__`, or a patch into it. Do not use the Chatbox desktop MSI to deploy this server plugin.
4. Use AstrBot's supported plugin reload after replacing the files. If this instance does not support plugin reload, perform a clean server restart using its normal service control. After loading, check AstrBot's plugin status/logs and verify configuration is still set in the server UI. No live generation call is part of this maintenance verification.

Keep instance paths and settings local. Do not put an absolute personal path, real UMO, provider ID, or credential into the repo.

## Roll back

Restore the **whole plugin directory** from the matching timestamped backup to `$dataDir\plugins\astrbot_plugin_chatbox_draw`, then use AstrBot's supported plugin reload. If this instance does not support plugin reload, perform a clean server restart using its normal service control. Preserve the backup until the restored version loads. Leave the AstrBot instance configuration and provider key untouched. The desktop MSI is independent of this rollback.
