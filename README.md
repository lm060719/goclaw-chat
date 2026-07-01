# GoClaw Chat (Android)

A native Android chat client for [GoClaw](https://goclaw.sh) — talks to your GoClaw gateway over
WebSocket (with a bit of REST on the side) so you get real streaming replies, thinking/reasoning
blocks, tool calls, file uploads, voice, and a bunch of admin-y stuff you'd normally only get from
a web dashboard, all from your phone.

Kotlin · Jetpack Compose (Material3) · Hilt · OkHttp (WebSocket + REST) · kotlinx.serialization ·
DataStore · Coil.

Package: `xyz.limo060719.goclaw` · minSdk 26 · target/compileSdk 35 · modules: `app`, `baselineprofile`.

> **Heads up:** the GoClaw *backend* lives elsewhere — this repo is just the Android client. Point
> it at any GoClaw gateway you have access to.

## What it actually does

**Chat**
- Streams replies over the gateway WebSocket (`chat.send` + `agent`/`chat` events), not plain
  request/response — you see thinking blocks and answer chunks arrive live.
- Multi-turn context is kept **server-side** per session, so the client only ever sends the latest
  message — no giant history payload on every turn.
- Tap stop mid-reply and it actually tells the server to abort the run (`chat.abort`), not just
  drops the socket.
- Tool calls show up as their own cards in the chat (name, input, result).
- Long conversations got you? You can compact server-side history from **Session Management**
  instead of blowing your context window every turn.

**Skills — two flavors**
- *Local (instruction) skills*: import a `.json`/`.md`/`.txt` file, or a proper Agent-Skill
  `SKILL.md` (with YAML frontmatter), or even a whole `.zip` (it'll find every `SKILL.md` inside
  and import them all). Enabled skills get prepended to your first message — the server remembers
  it for the rest of the session, so it's not re-sent every turn.
- *Backend (executable) skills*: upload a skill bundle straight to the gateway
  (`POST /v1/skills/upload`) so it can actually run scripts / load resources on demand, not just
  contribute text. The client automatically repositions `SKILL.md` to the zip root before
  uploading since the backend is picky about that.

**Media**
- Pick an image → it gets downscaled and sent as a multimodal content part.
- Attach files, and the agent can hand files back to you — tap to download them straight to
  `Downloads/GoClaw`.

**Voice**
- Mic button uses on-device `SpeechRecognizer` for input.
- Replies can be read aloud with on-device TTS (auto-picks a Chinese voice when the reply has
  Chinese in it, so you don't get English-voiced Chinese text), or you can flip a switch in
  Settings to use the backend's TTS provider for higher-quality voices — it silently falls back to
  on-device TTS if the backend synth fails, with a toast telling you why.

**Everything else that's normally a dashboard-only thing**
- **Gateway status**: a "test connection" button + online/offline dot right on the AI Provider
  screen.
- **Model picker that actually works**: load your configured providers, pick a model from the real
  list the provider offers, apply it to your agent — this calls `agents.update` server-side, it's
  not just a text field that gets silently ignored (yeah, that used to be a thing here — it's not
  optional anymore).
- **Usage & cost**: token counts, request counts, cost, LLM/tool call counts, pulled straight from
  `/v1/usage/summary`.
- **Traces**: browse recent LLM execution traces, tap one to see the full JSON.
- **Approvals**: if your agent needs a human to bless a shell command before running it, approve
  or deny it right from the app.
- **Session management**: list server sessions, compact/reset/delete them.

**Look & feel**
- Light/dark/system theme.
- Optional WeChat-style UI (custom avatars + names for you and the assistant).

## First run

1. Open in Android Studio — it'll sync and grab the Gradle wrapper. (Or run `gradle wrapper` once
   if you've got Gradle 8.9+ installed locally.)
2. Launch the app → **Settings → AI 供应商 (AI Provider)** → fill in the gateway URL, API key,
   user ID. Hit **测试连接 (test connection)** to make sure it's actually reachable.
3. Load agents, pick one. **Model is required now** — load providers, pick a model, apply it.
4. (Optional) **附加功能 (Extra Features)**: import a skill, upload a backend skill, check your
   usage, whatever.
5. Chat. Mic for voice input, image icon for pictures, speaker icon to toggle TTS.

## Where things live (if you're extending this)

- `data/remote/GoClawWsClient.kt` — the WebSocket RPC client. Streaming chat, session ops
  (list/compact/reset/delete), agent updates, exec approvals, gateway status — all the
  connect-once-and-RPC stuff lives here, built on a couple of generic helpers (`rpcBool`,
  `connectFrame`) so adding a new one-shot RPC is like 5 lines.
- `data/remote/GoClawApi.kt` — REST calls: agents, providers/models, usage, traces, backend
  skills, media upload/download, TTS synth. Parsing is deliberately tolerant (the backend's JSON
  shapes aren't always documented, or the docs are just wrong — see below) so a slightly different
  field name doesn't break the whole screen.
- `data/ChatRepository.kt` — the orchestration layer: session keys, skill injection, streaming
  event mapping, download/upload, TTS.
- `domain/tools/ToolRegistry.kt` — register a `Tool` (schema + handler) here if a skill needs to
  *do* something client-side, not just contribute instructions.
- `ui/settings/` — every one of those "extra feature" screens (Sessions, Usage, Traces, Backend
  Skills, Approvals) follows the same pattern: a `StateFlow`-based ViewModel + a Compose screen.
  Copy one if you're adding another admin-y screen.

### A note on the docs

The `.remark/` folder has offline PDF snapshots of the official GoClaw docs (WebSocket protocol,
REST API, endpoint catalog) — handy reference, and they *do* correspond to this backend. But two
things to know if you're touching the WS layer:

1. The docs describe streaming as flat `event:"chat"` / `payload.text`. The **real** gateway sends
   `event:"agent"` with the actual content nested under `payload.payload.content`. The client
   handles both, but trust the live frames over the docs if something looks off.
2. A bunch of response shapes (usage summary, for one) aren't documented at all — `current`/
   `previous` nesting, non-obvious field names, etc. If a new screen shows all zeros, it's
   probably a field-name mismatch, not a bug — go look at the raw response.

> Bump versions in `gradle/libs.versions.toml` as needed for your toolchain. This has been
> iterated against a live backend, but your mileage may vary depending on gateway version.
