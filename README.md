# GoClaw Chat (Android)

A native Android chat client for the GoClaw OpenAI-compatible API.
Kotlin · Jetpack Compose (Material3) · Hilt · OkHttp (raw SSE) · kotlinx.serialization · DataStore · Coil.

Package: `xyz.limo060719.goclaw` · minSdk 26 · target/compileSdk 35.

## Features
- **Streaming chat** against `POST /v1/chat/completions` (SSE), with `agent` + optional `model`.
- **Tool calling**: standard OpenAI function-calling loop (`tools[] -> tool_calls -> role:"tool"`),
  executed client-side and fed back automatically (up to 8 rounds). Built-in example tools:
  `get_current_time`, `get_device_info` — add your own in `domain/tools/ToolRegistry.kt`.
- **Skills import**: load `.json` / `.md` / `.txt` skill files; enabled skills are injected into the
  `system` message of every request. Persisted to `filesDir/skills.json`.
- **Image upload**: pick an image -> downscaled JPEG -> base64 data URI as an OpenAI content part.
- **Voice**: on-device `SpeechRecognizer` (STT) for input + `TextToSpeech` (TTS) for replies.
  No backend audio endpoint required.
- Agent discovery via `GET /v1/agents` (accepts `{data:[...]}` or a bare array).

## First run
1. Open in Android Studio (it will generate the Gradle wrapper jar and sync), or run
   `gradle wrapper` once if you have Gradle 8.9+ installed.
2. Launch the app -> **Settings** -> enter Backend URL + API Key -> *Load agents* -> pick one -> Save.
3. (Optional) **Skills** -> Import a skill file.
4. Chat. Tap the mic for voice input, the image icon to attach a picture, the speaker icon to toggle TTS.

## Architecture notes / extension points
- `data/remote/StreamingChatClient.kt` — SSE parser + streamed tool-call accumulator (by index).
- `data/ChatRepository.kt` — the agentic loop (system injection, streaming, tool execution, history).
- A skill that needs to *execute* a tool should register a `Tool` (schema + handler) in `ToolRegistry`;
  skill files currently contribute instructions only.
- Assistant text is rendered as plain `Text` — wire up a Markdown renderer if you want rich output.

> Scaffold delivered untested against a live backend / Android SDK build. Bump library versions
> in `gradle/libs.versions.toml` as needed for your toolchain.
