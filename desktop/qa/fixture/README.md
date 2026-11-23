# Isolated AstrBot UI fixture

This fixture supplies two synthetic local AstrBot OpenAPI origins for desktop UI acceptance. It uses only loopback HTTPS, a per-run certificate, an app-specific Java truststore, synthetic random credentials, and an isolated Chatbox data directory. It does not modify Windows machine or user certificate stores and does not contact a real provider.

## Create a run

From the repository root, run:

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\desktop\qa\fixture\Prepare-Fixture.ps1

The default run root is under D:\Chatbox验收 V1\ui-redesign\chatbox-fixture. Each invocation creates a new run directory and restricts it to the current Windows user. The script creates an ephemeral certificate valid for 127.0.0.1, compiles the two local Java helpers with the installed JDK, starts the service in a hidden process, and runs protocol probes. Before any request line is written, the service checks that it contains no configured credential value.

The output gives the exact paths for the run. Keep the run root for subsequent commands. It contains:

- localappdata\Chatbox\history.json: synthetic 0.1.5-era schemaVersion 2 history with stable manual profile IDs for A and B
- localappdata\Chatbox\credentials: one current-user DPAPI blob for A's original single key, encrypted with the exact manual profile ID entropy used by the app
- tls\fixture-app-trust.p12: private app-specific truststore with the fixture certificate
- private\fixture-private.properties: harness configuration with synthetic secrets
- private\service.log: request metadata and media verification receipts when enabled
- media: generated PNG and JPEG upload samples

The fixture does not preseed named-key metadata. On first use, the app recognizes its DPAPI blob as the legacy credential and migrates it through the normal key metadata path under the name 原有密钥. The private properties file is the only place where full synthetic API keys are exposed. Read it directly from the harness or a same-user interactive test process; do not copy its contents into screenshots, ordinary app settings, or logs. Its credential names are:

- token.a.original: A's previously valid chat and file key; preloaded as the legacy single key using DPAPI
- token.a.second: A's second valid chat and file key
- token.b.second: B's valid chat and file key
- token.invalid: known invalid candidate, rejected with HTTP 401

All values are randomly generated for each run. The fixture service maps each key to a local profile and accepts only the required chat and file endpoints; it has no admin API.

## Endpoint contract

Profile A is https://127.0.0.1:18443 and profile B is https://127.0.0.1:18444. Both share the ephemeral certificate and return data only from the local fixture:

- GET /api/v1/chat/sessions returns the corresponding profile session when the supplied username matches the synthetic history.
- POST /api/v1/chat returns ordinary text SSE, a saved PNG reply image for A or JPEG reply image for B, and a 6.5-second streamed response when the prompt contains 延迟.
- POST /api/v1/file accepts the app's multipart image upload and returns a synthetic attachment ID.
- GET /api/v1/file?attachment_id=reply-image-a returns a real PNG; reply-image-b returns a real JPEG.
- A request with token.invalid receives HTTP 401.

The service logs a safe key alias, endpoint, session ID when present, and result code. Each POST also emits a `received` line on arrival. Compare the counts of `received profile=A operation=upload` and `received profile=A operation=chat` before and after each UI action. Take the initial counts after preparation, which sends its own probe requests.

### Check attachment submissions and voice durations

Pass `-VerifyMedia` to `Prepare-Fixture.ps1` to enable the Python 3 standard-library verifier for the installed client. It checks multipart field `file`, image signatures, and readable PCM WAV frames for `audio/wav`. For chat it checks that each image or record segment includes a filename and references a distinct, matching upload to that profile. The `verified` lines give ordered segment types and fixture-generated upload IDs. An audio upload also reports its frame count, sample rate, and channel count. The verifier keeps only the attachment type in private receipt files.

Send `fixture:duration-pair` to receive one SSE response containing 8 seconds of mono audio and 65 seconds of stereo audio. Send `fixture:duration-edges` for 375 ms, 2.75 seconds with a RIFF metadata chunk, a corrupt WAV, and a missing WAV. Set `wav.repair.corrupt=true` and `wav.repair.missing=true` in the run's private controls file, then use the client's retry actions. The same attachment IDs will return 3.5 and 4.5 seconds of valid audio.

The private `controls.properties` file also supports focused send and upload cases. The two chat timing markers are included in the synthetic prompt, while the milliseconds can be adjusted in the control file:

- `fixture:delay-before-headers` waits `chat.delay.before.headers.ms` (default 1500) before returning SSE headers.
- `fixture:delay-before-first-event` waits `chat.delay.before.first.event.ms` (default 1500) after headers and before the first SSE event.
- `fixture:truncate-sse` returns a partial text event and closes the stream without an `end` event. Setting `chat.truncate.sse=true` does the same for any chat request.
- `upload.fail=true` returns HTTP 503 for file uploads. Set it back to `false` before a successful upload case.

For example, to make the next upload fail, add `upload.fail=true` to the run's private `controls.properties`; the setting is read for each request. These controls never write request content to the log. Keep the whole private run directory out of screenshots and shared artifacts.

To make A reject its previously valid key while keeping its second key valid, run:

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\desktop\qa\fixture\Set-Reject-Old-Key.ps1 -RunRoot 'D:\Chatbox验收 V1\ui-redesign\chatbox-fixture\run-...' -Reject $true

Return to normal acceptance behavior with the same command and -Reject $false. The control is a private local properties file and takes effect on the next request.

## Run the desktop app

### Exercise reading position during a streamed reply

Send `fixture:long-stream:run1` in a synthetic conversation. The fixture streams paragraphs 001–090 through the ordinary AstrBot chat endpoint, then waits. Scroll up until you can read a paragraph number near the top of the viewport. Record that number and its position before advancing each stage.

In PowerShell, set `$runRoot` to the path printed by the preparation script. Run each line separately and inspect the viewport after each one.

    New-Item -ItemType File -Path (Join-Path $runRoot 'private\long-stream-run1.text')
    New-Item -ItemType File -Path (Join-Path $runRoot 'private\long-stream-run1.image')
    New-Item -ItemType File -Path (Join-Path $runRoot 'private\long-stream-run1.end')

The text stage appends paragraphs 091–108. The image stage sends the image and its attachment ID. The end stage completes the reply. While waiting, the fixture sends SSE comments every five seconds to keep the connection alive.

To release two separate text batches, send `fixture:long-stream:run2 fixture:two-text-batches`. Create `private\long-stream-run2.text` for paragraphs 091–099, then `private\long-stream-run2.text2` for paragraphs 100–108. Continue with the `.image` and `.end` files for `run2`.

For another run, use a fresh ID in both the prompt and filenames. IDs accept 1–32 ASCII letters, digits, underscores or hyphens. To exercise follow resumption, scroll back to the bottom before advancing a stage. To exercise session switching, select another conversation before advancing the original stream.

### Launch against the isolated fixture

The launch script directs Chatbox to the isolated run's local app data and passes the Java truststore only to that process:

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\desktop\qa\fixture\Launch-Fixture-App.ps1 -RunRoot 'D:\Chatbox验收 V1\ui-redesign\chatbox-fixture\run-...'

The preloaded history uses stable per-profile IDs, so A's manual profile ID, manual:https://127.0.0.1:18443, matches the DPAPI blob and can auto-authenticate through the app's normal path. B is already present in the history but has no preloaded credential. To enter keys from the private harness config, keep the private file in memory in the interactive harness and paste only the selected synthetic value into the password field. B and the second key can be entered or added using the normal UI. The conversations are short product-design threads with saved local reply-image IDs. If a saved image is not shown immediately, click 重试获取图片 to fetch it from the fixture service.

For the packaged mouse exercise, launch this isolated app, create enough conversations to make the sidebar scroll, and use repeated first messages so several rows have the same title. Scroll to an older duplicate and select it, switch between conversations with unsent drafts, then start a `延迟` send and remove that conversation before its 6.5-second reply finishes. Confirm the reply does not recreate the removed row, restart the packaged app, and confirm the remaining conversation history and selection persist. Use only synthetic prompts and keep the private config, controls, credentials, and service logs out of screenshots.

The bundled Java 17 runtime supports the fixture's PKCS12 truststore. The launcher uses JAVA_HOME when it points to JDK 17 or 21; otherwise it selects the repository's acceptance JDK 17 or the JDK 17/21 on PATH. The launcher sets LOCALAPPDATA to the run-local localappdata directory and uses fixture-run.init.gradle to pass only the truststore path and type to the app's JavaExec task. The truststore contains the public synthetic certificate; no truststore password, API key, or other synthetic credential is passed on the command line or through JAVA_TOOL_OPTIONS. The launcher restores the caller's environment when the app exits. No Java truststore or certificate store outside the run directory changes.

Stop the local service after acceptance:

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\desktop\qa\fixture\Stop-Fixture.ps1 -RunRoot 'D:\Chatbox验收 V1\ui-redesign\chatbox-fixture\run-...'

Run logs and credentials remain in the run directory for review. Remove only the exact run directory when it is no longer needed; the fixture scripts do not recursively delete prior runs.
