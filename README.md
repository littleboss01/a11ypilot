# A11yPilot

> An on-device AI agent that drives your Android phone using the accessibility API. Talk or type, and Claude operates the phone for you. Bonus: the same tool surface is exposed as an MCP server on your local network so any MCP client can drive the phone too.

A11yPilot turns your phone into something you can address in natural language. Say "open Settings and turn on Bluetooth" and it actually opens Settings, navigates to Bluetooth, and flips the switch — no automation scripts, no UI test framework, no rooted device.

---

## Table of contents

- [Why this exists](#why-this-exists)
- [Agentic app development and testing](#agentic-app-development-and-testing)
- [What it does](#what-it-does)
- [Features](#features)
- [How it works](#how-it-works)
- [Tool surface](#tool-surface)
- [Requirements](#requirements)
- [Build and install](#build-and-install)
- [First-run setup](#first-run-setup)
- [Using the in-app agent](#using-the-in-app-agent)
- [Using A11yPilot as an MCP server](#using-a11ypilot-as-an-mcp-server)
  - [Connect from Claude Code](#connect-from-claude-code)
  - [Connect from Claude Desktop](#connect-from-claude-desktop)
- [Privacy and safety](#privacy-and-safety)
- [Limitations](#limitations)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [License](#license)

---

## Why this exists

Android automation today is a fragmented landscape of partial solutions:

- **Tasker / Macrodroid** are powerful but require you to author every flow by hand. They don't *understand* what's on screen.
- **UI Automator / Espresso** are testing tools — you write Java/Kotlin and run them from a host machine. Not something you reach for on the bus.
- **ADB shell scripts** demand a tethered laptop and intimate knowledge of every app's view hierarchy.
- **Voice assistants** (Google Assistant, Bixby) are closed black boxes that only understand what their vendor allows. They can't open your banking app, navigate your photo gallery, or fill in a form they've never seen.
- **Screen-recording macro tools** are brittle: they replay coordinates, so a single layout change breaks them.

The common gap: none of them can take a fuzzy human request and figure out the steps in real time on whatever screen happens to be in front of them.

A11yPilot does exactly that. It uses Android's built-in accessibility API (the same plumbing TalkBack uses) to read the live view tree of whatever app is in the foreground, hands a compact representation to Claude, and dispatches the action Claude picks. Then it reads the resulting screen and loops. No vendor lock-in, no coordinate-replay brittleness, no need for the app being driven to know anything about A11yPilot.

It also serves as a reference implementation for two things that are surprisingly hard to find good examples of:

1. **Agentic loops on Android** with real prompt caching, real cancellation, and real cost accounting.
2. **A working MCP server hosted on a phone** that an external client (Claude Desktop, etc.) can drive over Wi-Fi.

If you're building agents that need to touch real-world apps, or you're just curious how far the accessibility API can take you, A11yPilot is meant to be a useful starting point.

---

## Agentic app development and testing

Building AI agents that operate real apps — and using AI agents to test apps — has become one of the most active areas of 2025–2026. Anthropic's [Computer Use](https://docs.anthropic.com/en/docs/build-with-claude/computer-use), browser-driving agents (Playwright + LLM, browser-use, Browserbase), and the explosion of "agent-as-a-tester" tooling all share one gap: they target *desktop browsers*. Mobile apps — where most real users actually live — have been left out.

A11yPilot fills that gap by making your Android phone a first-class target for agentic workflows. Think of it as **Playwright MCP, but for Android**.

**Why this matters for app builders:**

- **Test your own app with an AI agent.** Connect Claude Code or Claude Desktop to a phone running your debug build via MCP, then ask: *"sign up with a new account, post a photo, then delete the account"*. Real device, real OS-level taps, real view tree. No flaky XPath, no maintained Espresso scripts that break on every redesign.
- **Smoke tests that survive UI changes.** A natural-language test (*"the checkout button should reach a payment screen"*) keeps working through layout refactors, because the agent re-discovers the path each turn instead of replaying coordinates or selectors.
- **Cross-app QA.** Drive scenarios that span multiple apps (*"share this from my app to WhatsApp and verify the message arrives"*) — something coordinate-based or single-app frameworks can't easily do.
- **Reproduce bugs from natural-language reports.** Hand Claude a customer's bug report with A11yPilot connected; it can attempt to reproduce on a real device and capture the failing screen.
- **Iterate without leaving your editor.** With Claude Code + A11yPilot, you can write a feature, then immediately ask Claude *"open my debug build, navigate to the new screen, and tell me what you see"* — agent loop runs on the phone, results land in your terminal.
- **Reference implementation.** If you're building your own mobile agent or MCP integration, the `ToolExecutor` / `ScreenSerializer` / Anthropic prompt-caching plumbing here is meant to be readable and copy-pasteable.

The skip-the-line trick: the same `ToolExecutor` powers both the in-app agent and the MCP surface. Anything you can do by typing into the app, you can do from Claude Code over Wi-Fi.

---

## What it does

You give A11yPilot an instruction in natural language — typed or spoken. It then enters a loop:

1. Read the current screen via the accessibility tree.
2. Send the screen + your instruction + conversation history to Claude.
3. Claude returns one tool call (`click`, `set_text`, `scroll`, `tap`, `swipe`, `screenshot`, ...).
4. A11yPilot dispatches it through `AccessibilityService`.
5. Wait for the screen to settle.
6. Re-read the screen and feed it back as the tool result.
7. Repeat until Claude calls `done` (or the step cap is hit).

While running, a draggable overlay shows live progress and a Stop button — visible across other apps so you can interrupt at any time. When the run finishes, A11yPilot brings itself back to the foreground and shows token usage broken down per turn.

The same tool dispatcher is also exposed as an HTTP server speaking JSON-RPC 2.0 over the [Model Context Protocol (MCP)](https://modelcontextprotocol.io). Toggle it on, point Claude Desktop (or any other MCP client) at the phone's IP, and the desktop AI can drive the phone over your local network.

---

## Features

- **Natural-language phone control.** Type or hold-to-talk. Claude figures out the steps.
- **Reads the screen via accessibility, not screenshots.** Cheaper, structured, password-aware. Falls back to screenshots only when the tree is empty (canvas UIs, games, video, charts).
- **Multi-step agent loop with prompt caching.** System prompt and tool schemas are marked `cache_control: ephemeral`, so per-turn input cost drops sharply after turn one.
- **Per-turn token accounting.** Input / cache_read / cache_creation / output broken out for every API turn so you can verify caching is working.
- **Push-to-talk voice input.** Holds the mic, streams partial transcripts, auto-runs on final.
- **Floating progress overlay.** Draggable, with a Stop button. Visible while the agent works in other apps.
- **Auto return-to-app.** When the agent finishes, A11yPilot brings itself back to the front so you see the result without hunting in Recents.
- **MCP server on your LAN.** Exposes the same tool surface as the in-app agent at `POST /mcp` with bearer-token auth. Drop-in compatible with Claude Desktop via the `mcp-remote` bridge.
- **Foreground service for MCP.** Server keeps running with the screen off; sticky notification shows the URL.
- **Encrypted credential storage.** API key and MCP bearer token live in `EncryptedSharedPreferences`, never logged.
- **Hard cancellation.** Stop button kills the coroutine cleanly; `done(false)` is also a valid escape hatch the model can call.
- **Configurable cost cap.** Slider in Settings sets the max tool calls per run (5–50).

---

## How it works

A11yPilot is an **agentic loop**: an LLM looks at the screen, picks one tool, the tool runs, the new screen is fed back, repeat until the LLM calls `done`. The LLM can be either Claude running in-app via the Anthropic API, **or** any external LLM connected through the MCP server. Both paths land on the same `ToolExecutor`, so behavior is identical.

```
                          ┌─────────────────────────────────────────────────┐
                          │                    LLM brain                    │
                          │                                                 │
   voice / typed ───────► │  ┌─────────────────┐    ┌──────────────────┐    │ ◄──── HTTP + Bearer
   prompt (in-app run)    │  │  Anthropic API  │    │   MCP client     │    │       over LAN:
                          │  │  Claude Sonnet  │ OR │   over HTTP /    │    │       Claude Code,
                          │  │  / Opus / Haiku │    │   JSON-RPC 2.0   │    │       Claude Desktop,
                          │  │  + prompt cache │    │   + Bearer auth  │    │       curl, anything
                          │  └─────────────────┘    └──────────────────┘    │       that speaks MCP
                          └────────────────────────┬────────────────────────┘
                                                   │  picks ONE next tool call
                                                   ▼
                          ┌─────────────────────────────────────────────────┐
                          │       ToolExecutor   (single dispatcher)        │
                          │                                                 │
                          │   click • set_text • scroll • tap • swipe       │
                          │   screenshot • launch_app • global • wait       │
                          │   dump_screen • done                            │
                          └────────────────────────┬────────────────────────┘
                                                   │
                                                   ▼
                          ┌─────────────────────────────────────────────────┐
                          │           PilotAccessibilityService             │
                          │                                                 │
                          │   • reads view tree → ScreenSerializer → DSL    │
                          │   • performAction · dispatchGesture · setText   │
                          │   • takeScreenshot (vision fallback, opt-in)    │
                          └────────────────────────┬────────────────────────┘
                                                   │
                                                   ▼
                            Android view tree of whichever app is in front
                                                   │
                                                   └─── new screen + screenshot
                                                        ◄───── fed back to the LLM
                                                              as the tool result,
                                                              loop continues until
                                                              the LLM calls `done`
```

Why this shape matters:

- **The LLM is the planner; the phone is the actuator.** A11yPilot does no planning. Every decision — which button to tap, when to scroll, when to give up — is the model's. That's what makes a fuzzy instruction like *"book me a 9 a.m. cab tomorrow"* work without any per-app scripting.
- **Bring-your-own LLM via MCP.** The in-app loop uses the Anthropic API directly because that's what's optimized for tool-use and prompt caching today, but the MCP surface is model-agnostic — anything that speaks the MCP spec can drive the phone. Connect it to Claude Code, Claude Desktop, or your own agent harness using OpenAI/Gemini/local models behind an MCP-compatible client.
- **One tool dispatcher, two callers.** Both `AgentEngine` (in-app) and `JsonRpc` (MCP) route through `ToolExecutor`. A bug fix or new tool in one place is automatically available to the other.
- **Compact screen DSL beats raw screenshots.** `ScreenSerializer` filters `rootInActiveWindow` to visible + actionable + has-text nodes, collapses single-child wrappers, and emits one line per element: `[id] ShortClass "text" ?hint *!…`. Typical screen serializes to a few hundred tokens vs the ~thousands a vision-only approach burns per turn — and it works on most apps without a screenshot at all.
- **IDs are turn-scoped.** Each tool result returns fresh ids. The model is told ids are valid only for the current turn, so the executor builds a compact `idMap` per call with no persistent state.
- **Prompt caching pays off fast.** System prompt + tool schemas are marked `cache_control: ephemeral`. After turn 1, per-turn input cost drops sharply — visible in the per-turn token breakdown the UI shows after each run.
- **Settle wait, not sleep.** After every action, `awaitSettle()` waits until no `AccessibilityEvent` has fired for 250 ms (capped at 1.5 s). Faster than fixed sleeps, more reliable than polling.
- **Screenshots are opt-in vision.** The `screenshot` tool is explicitly documented as expensive in the system prompt, so the LLM only reaches for it on canvas UIs, games, video, or when it needs to verify a visual state.
- **MCP runs in a foreground service.** Ktor CIO bound to `0.0.0.0:<port>`, sticky notification, survives screen-off. Bearer token generated on first enable, stored in `EncryptedSharedPreferences`, never logged.

---

## Tool surface

Every tool returns the freshly serialized screen so the model never wastes a turn calling `dump_screen` after a successful action.

| Tool | Arguments | Notes |
|---|---|---|
| `dump_screen` | — | Re-read and return the current screen tree. |
| `screenshot` | — | JPEG of the display + screen tree. Use only when the tree is insufficient. |
| `click` | `id:int` | Click the node with the given id. Climbs to clickable ancestor if needed. |
| `long_click` | `id:int` | Long-press. |
| `set_text` | `id:int, value:string` | Replace text in an editable node. Submit explicitly afterward. |
| `scroll` | `id:int, direction:up\|down\|left\|right` | Scrolls the node or its nearest scrollable ancestor. |
| `tap` | `x:int, y:int` | Raw coordinates. Fallback when no node matches. |
| `swipe` | `x1,y1,x2,y2:int, duration_ms?:int` | 50–3000 ms. |
| `global` | `action:back\|home\|recents\|notifications` | System navigation. |
| `launch_app` | `package:string` | Launch an app by package name. |
| `wait` | `ms:int` (≤3000) | Wait for the screen to update. |
| `done` | `success:bool, summary:string` | Terminate the loop. |

---

## Requirements

- **Android 11 (API 30) or later.** `screenshot` and the `takeScreenshot` API need API 30+; the rest of the agent technically works on API 24, but the build targets API 30+ for vision support.
- **Anthropic API key** for the in-app agent. Get one at [console.anthropic.com](https://console.anthropic.com). The MCP server does not need a key — it just exposes tools to whatever AI the client provides.
- **Wi-Fi network** if you plan to use the MCP server from another machine. Cellular is blocked by a UI warning.
- **Node.js** on the desktop side if you want to use the MCP server from Claude Desktop (uses the `mcp-remote` stdio↔HTTP bridge). Not needed for `curl` or other HTTP clients.

---

## Build and install

```bash
git clone https://github.com/azizahmed45/a11ypilot.git
cd a11ypilot
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in Android Studio and hit Run.

The repo has no proprietary dependencies. All deps are declared in `gradle/libs.versions.toml` and resolved from Google + Maven Central.

---

## First-run setup

Open the app. The Setup card walks through three steps:

1. **Enable the accessibility service.** Tap *Enable*, find *A11yPilot* in Android's accessibility settings (often under Downloaded / Installed apps), toggle it on, confirm the security prompt, press back. The Setup card's checkmark turns green.
2. **Add your Anthropic API key.** Tap *Add key*, paste an `sk-ant-…` key, optionally pick a different model (default `claude-sonnet-4-5`), save. The key is stored in `EncryptedSharedPreferences`.
3. **Grant overlay permission (optional).** Required only for the floating progress + Stop button while the agent runs in other apps. Without it the agent still works, but you'll need to swipe back to A11yPilot to see status.

When all three rows are green, you're ready.

---

## Using the in-app agent

Type an instruction in the *Tell the agent what to do* box and tap Run, or hold the mic icon, speak, release. Examples bundled in the *Try this* card:

- *Open Settings and turn on Bluetooth*
- *Open Chrome and search for anthropic*
- *Open the clock app and start a 5 minute timer*
- *Open WhatsApp and read my latest message*
- *Open YouTube and play lo-fi beats*
- *Take a screenshot and open it*

While running:

- A draggable overlay shows the current step and live token totals.
- The Stop button on the overlay (and Cancel in-app) cancels the coroutine immediately.
- The Live event log at the bottom of the screen streams every dispatched tool call and every API turn's token accounting.

When the run ends:

- A11yPilot returns to the foreground.
- The Run details card shows total steps, success/fail, summary, and the per-turn token breakdown.
- Cache hits should appear from turn 2 onward — that's the prompt cache working.

---

## Using A11yPilot as an MCP server

A11yPilot speaks [MCP](https://modelcontextprotocol.io) over Streamable HTTP at `POST /mcp` with bearer auth. The same tool surface as the in-app agent.

### Enable it

Toggle the *MCP server (LAN)* card on. The card reveals:

- **Status** — green when bound and on Wi-Fi.
- **IP** — the phone's active IPv4 (with copy button).
- **Port** — defaults to 8765, editable while off.
- **Endpoint URL** — `http://<ip>:<port>/mcp`.
- **Bearer token** — auto-generated on first enable; reveal/copy/regenerate.

A foreground notification appears showing the URL — that's how the server survives screen-off.

### Quick test (curl)

```bash
curl -H "Authorization: Bearer <token>" \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' \
     http://<phone-ip>:<port>/mcp
```

You should get back the full tool list. The card has a copy-to-clipboard button that pre-fills your token and URL.

### Connect from Claude Code

[Claude Code](https://docs.claude.com/en/docs/claude-code) speaks HTTP MCP natively — no bridge needed. From any terminal:

```bash
claude mcp add --transport http a11ypilot http://<phone-ip>:<port>/mcp \
  --header "Authorization: Bearer <token>"
```

Or check it in to a project's `.mcp.json` so your whole team picks it up:

```json
{
  "mcpServers": {
    "a11ypilot": {
      "type": "http",
      "url": "http://<phone-ip>:<port>/mcp",
      "headers": {
        "Authorization": "Bearer <token>"
      }
    }
  }
}
```

Verify it's wired up:

```bash
claude mcp list
```

Then inside Claude Code you can say things like:

- *"Use a11ypilot to open my debug build and screenshot the new onboarding screen."*
- *"Sign up a fresh account on the phone, then tell me what HTTP requests show up in the logs."*
- *"Reproduce the bug in issue #42 on the phone and capture the failing screen."*

Because A11yPilot's MCP surface is the same dispatcher the in-app agent uses, anything you've manually verified in the app works identically from Claude Code. That makes it especially handy for tight dev loops: edit code → rebuild → ask Claude to drive the new flow → read the screenshot or screen tree it returns, all without leaving your editor.

> **Tip for testing your own app:** scope Claude's session to your package by mentioning it in the prompt (e.g. *"only operate on `com.example.myapp`"*). A11yPilot doesn't enforce this, but Claude will respect it and you'll get cleaner runs.

### Connect from Claude Desktop

Claude Desktop currently doesn't speak HTTP MCP directly (unlike Claude Code), so we use the [`mcp-remote`](https://www.npmjs.com/package/mcp-remote) stdio↔HTTP bridge (Node.js required).

Add to `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "phone": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote",
        "http://<phone-ip>:<port>/mcp",
        "--allow-http",
        "--header",
        "Authorization: Bearer <token>"
      ]
    }
  }
}
```

The MCP card in the app generates this snippet with your real values pre-filled. Restart Claude Desktop and ask it to *"open my phone's Settings app"* — it'll dispatch through the same `ToolExecutor` the in-app agent uses.

### JSON-RPC methods

| Method | Purpose |
|---|---|
| `initialize` | Returns protocol version + server info. |
| `ping` | Health check. |
| `tools/list` | Returns the tool surface (camelCase `inputSchema` per MCP spec). |
| `tools/call` | Invokes a tool. Returns `{content: [{type:"text"/"image", ...}], isError}`. |

---

## Privacy and safety

- Screen contents (text + structure, sometimes a screenshot) are sent to Anthropic's API on every turn while a run is active. Don't run the agent on screens with sensitive content you wouldn't paste into a chat.
- Password fields (`isPassword`) are masked to `••••` before serialization.
- API key and MCP bearer token live in `EncryptedSharedPreferences` and never appear in logs or the in-app event log.
- The MCP server is **off by default**. When on, it binds `0.0.0.0` — only enable on a Wi-Fi network you trust. The card shows a red banner if you're on cellular only.
- Hard cap on tool calls per run (default 25, slider 5–50). Cancel anytime via the floating Stop button.
- A11yPilot's own package is excluded from MCP screen dumps so a remote client can't see (or tap) the toggle that disables itself.
- The app does no telemetry, no crash reporting, no analytics. The only outbound traffic is to Anthropic's API (when the agent runs) and to whatever MCP client connects.

---

## Limitations

- **Secure surfaces return blank screenshots.** Banking apps, DRM-protected video, and the lock screen set `FLAG_SECURE`; `takeScreenshot` honors that. The accessibility tree is also blocked on some such surfaces.
- **One screenshot per second per display.** Platform rate limit — the model is told to use `screenshot` sparingly anyway.
- **`rootInActiveWindow` only.** No multi-window or split-screen targeting today.
- **No persistent memory across runs.** Each run starts fresh. By design — the model can re-read the screen, so there's nothing to remember.
- **Speech recognition uses the platform `SpeechRecognizer`.** Quality depends on the device's installed Google offline model; airplane mode breaks it on devices without local recognition.
- **No URL-based external exposure.** The MCP server is LAN-only. A relay/tunnel client would let it work over the internet but isn't built.
- **Single-display assumption.** `Display.DEFAULT_DISPLAY` for screenshots; foldables and external displays are untested.

---

## Roadmap

Concrete items that are in scope for contributions:

- [ ] **Tunnel/relay client** for MCP so the phone can be addressed from outside the LAN without exposing 0.0.0.0.
- [ ] **Persistent memory** across runs (notes file the agent can read/write).
- [ ] **OpenRouter / local-LLM client** so users aren't locked to Anthropic.
- [ ] **iOS port** — same architecture, different accessibility plumbing.
- [ ] **Per-app system-prompt overrides** so power users can teach the agent quirks of specific apps.
- [ ] **Screen recorder of the agent's session** for debugging long runs.
- [ ] **Headless mode**: run agent loop from `adb shell am broadcast` for CI/scripting.

Out of scope for now:

- Always-on listening (privacy/battery cost too high).
- A custom launcher / replacement Android UI.
- Cloud-hosted agent backend.

---

## Contributing

Contributions are very welcome — issues, PRs, and design discussion all useful.

**Before you open a PR:**

1. Open an issue first if it's a non-trivial change. Saves both of us time.
2. Run `./gradlew :app:lint :app:assembleDebug` and make sure both pass.
3. Match the existing code style — Kotlin official, 4-space indent, no wildcard imports.
4. New tools added to `ToolExecutor` must also have a schema entry in `Prompts.anthropicTools()` and a dispatch case in both `AgentEngine.dispatch` and `JsonRpc.toolsCall` — this keeps in-app and MCP behavior aligned.
5. Don't bloat the system prompt without measuring the token impact.

**Good first issues:** anything tagged `help wanted` in GitHub issues, or pick from the Roadmap.

---

## License

Apache License 2.0. See [LICENSE](LICENSE).

This project is not affiliated with Anthropic. "Claude" and "Anthropic" are trademarks of Anthropic, PBC. "Android" is a trademark of Google LLC. "MCP" / "Model Context Protocol" is an open specification published by Anthropic.
