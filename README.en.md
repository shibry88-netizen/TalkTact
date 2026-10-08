# TalkTact · WeChat Chat Copilot (Xposed module)

[中文 README](README.md) ｜ **English**

TalkTact puts a **small button** in the top-right corner of a WeChat chat screen. Tap it and you get a card with
**a risk read + 3 reply candidates** (safe / witty / push-forward). Reading the last few messages, calling an
LLM-compatible endpoint of your choice, and **filling the reply box** is all it does — it **never sends** for you.
The card is collapsed by default, so it does not cover your messages.

Current version: **v0.8.11**. See [CHANGELOG.md](CHANGELOG.md) for the history and
[IMPROVEMENTS.md](IMPROVEMENTS.md) for the design notes.

---

## Compatibility

Since v0.7.0 this module targets the **libxposed Modern API 102** (`minApiVersion=102`), so the framework itself
must provide API 102:

| Framework | Status |
| :--- | :--- |
| **LSPosed / Vector** | ✅ Requires libxposed API 102. With an older framework the module shows as incompatible and is never loaded — upgrade the framework first. |
| **NPatch** (no root) | ✅ Supports the modern API 102. |
| **Original LSPatch** | ❌ Not supported. It was archived in Dec 2023, before libxposed's modern API; its loader only understands the legacy `assets/xposed_init`, so it "does not recognise" modern modules at all. Use NPatch instead. |

> The no-root (NPatch) route patches the target APK: scope is chosen when patching, not via the module's `scope.list`.
> Make sure patched WeChat can log in on its own before worrying about the module.

Tested against **WeChat 8.0.78**.

## Features

- **Reply card** — floats over the chat screen; reads the last N messages, gives 3 candidates with different
  strategies (safe / witty / push-forward). Tap to fill the input box, long-press to copy.
- **Multiple skills** — ships with several built-in prompts; you can also paste the URL of a `SKILL.md` on GitHub
  and the JSON output contract is added automatically.
- **Per-contact profiles** — archives what you chat about, keyed by WeChat name (the same line is kept once per
  hour). Write "who this person is to you" and "your relationship", and both (plus the archived lines) are folded
  into the prompt.
- **Glassomorphic UI** — the gradient background is drawn by the app itself (no bundled image); cards are
  translucent with a top highlight and a bottom edge. "Glass strength" is adjustable, and you can also use your own
  image (Android 13+ additionally gets real background blur + edge refraction behind the panel). Low-memory devices
  degrade automatically, and you can pin it manually (**Settings → Appearance → effect quality**).
- **★ Best pick + per-reply rewrite** — the recommended candidate is marked with a one-line reason
  (`best` / `why` in the output contract); each candidate has a ✎ button (**shorter / more formal / rephrase**) that
  rewrites **only that one**. Replies over 60 characters are flagged "· long".
- **Direct / graded mode** — the default "direct" mode uses one endpoint and one call. Switch to "graded" and the
  work is split into two **parallel** lanes (one judges intent + risk, one writes the 3 replies) merged into one
  card. Leave the second endpoint blank to reuse the first; **if one lane fails the other still renders**
  (a failed risk lane is shown as "not assessed") instead of showing an empty card.
- **Local proxy: the API key never reaches the WeChat process** — the app runs a foreground service bound to
  `127.0.0.1` only (random token required). The key stays in the app process; WeChat only talks to loopback and the
  app forwards the request.
- **Chat whitelist** — only the chats you tick work: everything else is not read, not archived, not sent. A chat
  whose name cannot be recognised counts as **not ticked** (fail-closed). Tap "pull chat list" → go back to WeChat's
  home / contacts list for two seconds → come back and tick; chats you opened are collected too.
- **Offline OCR for images** — text inside a chat image is recognised **on device** (ML Kit Chinese model) and used
  as the content of that message. **Nothing is uploaded, nothing goes online, nothing is written to disk**; the
  image only travels between the two local processes over loopback. If it cannot be recognised it falls back to the
  `[image/emoji/voice]` placeholder. The price is a bigger APK (~70 MB).
- **Steadier answers** — optional **strict JSON output** (only on the candidate-generation path); one automatic
  corrective retry when the model ignores the contract; when output is cut off by `max_tokens` the finished replies
  are salvaged instead of failing the whole call.
- **Diagnostics bundle** — "Advanced → Backup" exports a `.zip` (environment / config / profile counts /
  diagnostics / last call / usage). It **contains chat content and no API key**, so you can share it when reporting
  an issue.
- **Self-checks** — heartbeat (is the module really running inside WeChat right now), endpoint self-test, sensitive
  content check, view-hierarchy diagnostics, and the exact payload that was actually sent.

## Signing

The keystore used by CI is **not** in the repository — it lives in repository secrets
(`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEYSTORE_ALIAS`) and is restored to `keystore/talktact.p12` before the
build. To sign the same APK locally, put that file back in `keystore/` and set `KEYSTORE_PASSWORD`; **if the file is
missing the build falls back to the default debug signature**, which is fine for local work.

A fixed signature is mandatory: GitHub Actions runs on a fresh runner, so the default debug keystore is regenerated
every time and every build would get a different signature — updating in place would fail with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE (-7)`.

An **encrypted backup** of the key also lives in the repo (`signing/key.p12.enc`, AES-256-CBC + PBKDF2 with 600000
iterations + salt). CI prefers `KEYSTORE_BASE64` and only falls back to this archive, so **losing the secrets does
not lose the key**. The passphrase is not in the repository; see [signing/README.md](signing/README.md).

> ⚠️ **The signing key was rotated in 0.8.8.** Versions up to 0.8.7 used a different key that had been committed to
> this public repository **together with its passphrase** — effectively a public private key, letting anyone sign an
> APK that an existing install would accept as an update. It has been revoked and rotated.
>
> Therefore: **upgrading from 0.8.7 or earlier to 0.8.8+ requires uninstalling the old version first** — your
> configuration goes with it, so export a backup under "Settings → Backup / migration" and import it afterwards.
> Please also download only from the module repository or this repository's Releases, which publish a sha256.

## Requirements

- **Android 12 or newer (API 31+)** — `minSdk = 31`. The real background blur behind the glass panel uses
  `Modifier.blur` (backed by `RenderEffect`), which only exists from Android 12 onwards.
- **A framework providing libxposed API 102**: LSPosed / Vector, or root-free NPatch.
- Verified on WeChat **8.0.78**.

## Building

```bash
# Android Studio can open this directory directly
gradle :app:testDebugUnitTest   # pure logic unit tests, a few seconds, no device needed
gradle :app:assembleDebug       # produces app/build/outputs/apk/debug/app-debug.apk
```

Pushing runs tests and builds an APK on GitHub Actions (see `.github/workflows/build.yml`).

## Installing

1. Install the APK (package `io.github.shibry88_netizen.talktact`) → enable "TalkTact" in LSPosed → **tick WeChat in
   scope** → force-stop WeChat and reopen it.
2. Open the app: fill in the endpoint (OpenAI-compatible, including `/v1`), API key and model under "Settings".
3. Back on the home screen, tap "endpoint self-test" — it really sends one minimal request so you can confirm the
   address / key / model are right.
4. Open a chat in WeChat; a small "copilot" button appears in the top-right corner. Tap it to expand the reply card.

**Changing settings does not require restarting WeChat**: the configuration is pushed to the WeChat process live.
Only LSPosed-level changes (such as scope) need a force-stop.

## Privacy

In one sentence: **it only reads the few chat lines you can see right now, and sends them to the endpoint you
configured. It never sends automatically, never reports anywhere, and never touches the WeChat database.**

- Chat content, profiles and skill prompts go to **the endpoint you configured** (OpenAI-compatible). Make sure you
  trust it.
- The API key is stored in plain text in the app's private directory by default — injected code in WeChat has to read
  it across processes, and Keystore keys are per-UID, so encrypting it would make it unreadable. Turning on
  "Advanced → Local proxy" keeps the key inside the app process.
- **Image OCR happens on device**: the image only travels over loopback between the two processes, is recognised by
  an offline model, and is never uploaded or written to disk.
- The IP-geolocation lookup is the **only** third-party request this module makes, and it can be disabled.
- Messages that trip the sensitive-content check are **not archived into profiles**, so they can never end up in a
  future prompt either.

Full details (what is read, where it goes, what is stored, how to delete it, and what is explicitly out of scope):
[publish/PRIVACY.md](publish/PRIVACY.md).

## Known limitations

- Reliable for 1:1 chats only; group chats try to include nicknames, but their row structure changes often.
- Depends on WeChat's current message-list widget types; a WeChat update may break it.
- The APK is large (~70 MB): it bundles the ML Kit Chinese OCR model and native libraries — that is the price of
  offline recognition.
- Profile history is **partial**: the module only sees the lines that are on screen while you are in that chat.

## Layout

```
app/src/main/java/dev/goutou/wingman/
├── MainActivity.kt / ModuleStatus.kt / HeartbeatReceiver.kt   app entry, module probe, heartbeat
├── config/
│   ├── Config.kt           configuration read/write (shared key space with the injected side)
│   └── Roles.kt            profile archival / 1-hour dedup / serialisation (pure functions)
├── llm/
│   ├── Json.kt             minimal hand-written JSON
│   ├── Suggestion.kt       parsing and tolerance for model output
│   ├── Prompt.kt           built-in prompts
│   └── LlmClient.kt        OpenAI-compatible client (HttpURLConnection on purpose)
├── wechat/
│   ├── Snapshot.kt         plain intermediate representation (view tree → snapshot)
│   ├── ChatParser.kt       ★ snapshot → chat log: pure function, unit-testable
│   ├── Chrome.kt           rules for non-content rows (timestamps, unread counts, group labels)
│   ├── Sensitive.kt        local privacy check before sending
│   ├── ViewReader.kt       ★ the only part that depends on Android: view tree → snapshot
│   └── Overlay.kt          the floating card and its lifecycle
└── ui/
    ├── Ui.kt               theme / backdrop / glass components
    └── Screens.kt          the screens (status / try-it / mentor / profiles / settings)
```

## Support

- FAQ (risk of a ban, root or not, what to do when messages cannot be read): [publish/FAQ.md](publish/FAQ.md)
- Privacy and data boundaries: [publish/PRIVACY.md](publish/PRIVACY.md)
- Issues: <https://github.com/shibry88-netizen/TalkTact/issues>

## License

GPL-3.0 — see [LICENSE](LICENSE).
