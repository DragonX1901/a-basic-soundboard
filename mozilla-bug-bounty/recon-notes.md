# Recon notes — HTML-fragment / Sanitizer mitigation surface

A worked example of how to read a real Firefox mitigation, form a hypothesis,
and — importantly — recognize when the "suspicious" thing is actually *correct*
so you don't file a non-bug and hurt your account standing.

These notes are from reading `mozilla-central` at commit
`023cb8315420` (Sep 9 2026). Line numbers drift; re-check on Searchfox.

## The mitigation, in Mozilla's words

> *"We sanitize HTML fragments before using them in privileged contexts. A
> bypass would be (i) finding a location we should be sanitizing (because it has
> attacker-controlled data) but aren't, or (ii) bypassing the HTML sanitizer
> with something that could execute JS in Firefox."*

So there are two payable shapes here:
- **(i) A missing sanitization sink** — chrome/`about:` code that pushes
  attacker-controlled data into an HTML-fragment sink (`innerHTML`,
  `outerHTML`, `insertAdjacentHTML`, `DOMParser`, `setHTMLUnsafe`, ...) with no
  sanitizer in the path.
- **(ii) A sanitizer escape** — a payload that survives `Sanitizer` / the
  tree sanitizer and still runs script in a privileged context.

## How the enforcement is actually wired

`dom/base/nsContentUtils.cpp`:

- `ShouldSanitize(principal, flags)` — for the privileged default path
  (`kParseFragmentPrivilegedDefaultSanitization`, the `-1` default) it returns
  `true` when `principal->IsSystemPrincipal() || principal->SchemeIs("about")`.
  So **System-principal and `about:` fragment parsing is sanitized by default.**
- `ComputeSanitizationFlags(...)` then adds `SanitizerDropForms` for System
  principal, allows styles/comments, and logs removals.
- **`setHTML()`** uses `kParseFragmentNoSanitization` (`-2`) and its *own*
  `Sanitizer` object (the safe-listed WICG Sanitizer API), so `ShouldSanitize`
  returns `false` for it — sanitization happens through the `Sanitizer` class
  instead (`dom/security/sanitizer/Sanitizer.cpp`).

### The build-time tripwire (why this is well-defended)

`dom/security/DOMSecurityMonitor.cpp` → `AuditParsingOfHTMLXMLFragments()`:

On **debug/ASAN builds**, *any* call to the fragment parser from System-principal
or `about:` JS that isn't on an explicit **`htmlFragmentAllowlist`** hits
`MOZ_ASSERT(false)` and dumps the JS stack. The allowlist is small and
peer-reviewed (marquee.js, some devtools/react bundles, newtab bundle, an AI
window component, test harnesses). Practical implications for a hunter:

- Any *new* unsanitized privileged `innerHTML` sink is meant to **crash the
  assertion during CI/testing** — which is exactly why easy (i)-type bugs are
  rare in shipping code.
- Conversely, the **allowlisted files are the interesting audit targets**: they
  are *permitted* to call the fragment parser, so if attacker-controlled data
  can reach one of them, sanitization may not be doing what you'd assume. Worth
  reading each allowlisted file and tracing its data sources. (`newtab`
  activity-stream bundle and the `about:` React bundles are the juiciest —
  large, data-driven, remote-influenced content.)

## Candidate I looked at: the AI window chat sanitizer

`browser/components/aiwindow/ui/components/ai-chat-message/ai-chat-message.mjs`
renders **model output** (attacker-influential if the model is steered) via:

```js
element.setHTML(parseMarkdown(markdown), {
  sanitizer: AIChatMessage.#chatMessageSanitizer,
});
```

- `parseMarkdown` (`ChatMarkdownParser.mjs`) uses `MarkdownIt("default",
  { html: false })` — raw HTML in the markdown is **disabled**, good.
- It renders through `setHTML` (the *safe* Sanitizer path, not `setHTMLUnsafe`),
  and the custom `#chatMessageSanitizer` only **adds** an allowed element
  (`ai-chat-table`) and a couple of data attributes on top of the safe default
  config.
- `link_open` is overridden to drop empty-destination anchors; the sanitizer's
  `ShouldRemoveJavascriptNavigationURLAttribute` (Sanitizer.cpp) strips
  `javascript:` on `a/area/@href`, `form/@action`, `iframe/@src`, SVG/MathML
  href, etc. via real `NS_NewURI` scheme parsing.

**Verdict: not a bug (as-is).** `html:false` + `setHTML` safe-mode + additive-
only custom config is the *correct* defense-in-depth pattern. Filing "AI window
renders model output as HTML" without an actual sanitizer escape would be an
`Invalid` — avoid.

**But it IS a good place to keep digging**, because the payoff would be high:
- The custom sanitizer path is `setHTML` **safe** mode → confirm
  `aSafe`/`removeJavascriptNavigationUrls` is honored on *every* branch,
  including the `dom.security.sanitizer_while_parsing` on/off code paths
  (`nsContentUtils::SetAndFilterHTML` chooses parse-time vs. post-parse
  sanitization based on that pref — two code paths to compare for parity).
- Trace whether markdown can smuggle a raw-HTML-ish token that `MarkdownIt`
  emits *unescaped* into an allowed element/attribute (e.g. via the custom
  `ai-chat-table` renderer rule's attribute string building) — the table rule
  builds attributes with `md.utils.escapeHtml`, so check every renderer rule
  that emits markup for one that forgets to escape.
- Check whether `ai-chat-table`/`ai-chat-grid`/`ai-chat-card` custom elements
  do anything unsafe with attributes they receive *after* sanitization (a
  sanitized DOM can still feed a custom element that itself does
  `innerHTML`/`eval`/URL navigation — the sanitizer only guards the fragment,
  not what components do next).

That third bullet is the general lesson: **the sanitizer guarantees the parsed
tree is clean, not that downstream component code is safe.** Post-sanitization
sinks in privileged custom elements are a classic real bug class.

## Parity / pref-path checklist (generic, reusable)

When a mitigation has two code paths, bugs hide in the *asymmetry*:

- `dom.security.sanitizer_while_parsing` true vs false — same result?
- `sanitizeWhileParsing ? sanitizer.get() : nullptr` — is the sanitizer applied
  in both branches, or can one branch reach `AppendChild` unsanitized?
- System principal vs `about:` principal — `SanitizerDropForms` is
  System-only; is that intended for every `about:` page that renders remote
  content?
- `kParseFragmentNoSanitization` callers — every caller of `setHTML`/the
  no-sanitization flag is asserting the `Sanitizer` object covers it. Enumerate
  them (`grep kParseFragmentNoSanitization`) and verify each really passes a
  safe sanitizer.

## What would actually be payable here

- A privileged file (ideally already on the `htmlFragmentAllowlist`, or a *new*
  sink that somehow doesn't trip the assertion in a shipping config) where
  **web/remote-controlled** data reaches a fragment sink and yields **script
  execution with System/`about:` privileges** → HTML-sanitizer mitigation
  bypass, **High ($3,000)**, **+50%** if you reach it without privileged access
  → up to a UXSS-tier payout. Requires a concrete PoC that runs JS, not just a
  theoretical missing-sanitize argument.

## Next surfaces worth the same treatment

- `grep -rn "setHTMLUnsafe\|insertAdjacentHTML\|outerHTML\|DOMParser" browser/
  toolkit/ services/` filtered to `.sys.mjs`/chrome `.mjs` and cross-referenced
  with data provenance.
- The `eval()` allowlist (`nsContentSecurityUtils` filename/eval parser,
  `dom/security/test/gtest/TestFilenameEvalParser.cpp` shows the expected
  format) — find a reachable parent-process `eval` not covered by the allowlist.
- Xray wrapper confusion in `js/xpconnect/wrappers` (the client page literally
  points at bug 929539's dependencies as the pattern to look for).

*Nothing in these notes is a confirmed vulnerability. They are a research
starting point and an example of eligibility reasoning. Verify against live
source before acting.*

---

## Sec-high hunt — leads chased and ruled out (honest audit trail)

Pursuing a higher-severity (sec-high: UXSS / sandbox escape / high-value disclosure)
issue in the Smart Window. Each concrete lead below was ruled out with a specific
reason — recording them so the effort isn't repeated and so nothing here gets filed
as an Invalid.

1. **Escalate the thumbnail SSRF to code exec via `javascript:`** — tested live:
   `captureThumbnail("javascript:fetch(...)")` does not execute (hangs, no listener
   hit). No chrome code-exec via that scheme.
2. **Thumbnail browser runs attacker JS in the parent process** — `BackgroundPageThumbs`
   creates the thumb `<browser>` with `type="content" remote="true"` (OOP; has
   `oop-browser-crashed` handling). Attacker page JS therefore runs in a *content*
   process under the page's own origin — not the parent, not system principal. No
   sandbox escape.
3. **`file://` local-file read via the system-principal thumbnail load** — tested:
   returns `null` (no render). Not exploitable (already folded into finding 01).
4. **`MonitorAgent._openWatchedUrl` → `openTrustedLinkIn(url)` (system principal, no
   scheme check)** — reachable only with `url = monitor.watchUrls[0]`, and
   `trimAndFilterWatchUrls`/`isAllowedWatchUrl` enforce http/https at creation AND
   update in the parent. A `javascript:`/`file:` watchUrl can never be stored, so the
   open path only ever gets http/https. Defense-in-depth inconsistency (it should
   validate at use, not rely on the creation filter), **not** an exploitable bug.
5. **`AIWindowUI.reopenConversationInTab` / `#handleTopSiteSelected` →
   `openTrustedLinkIn` (system principal)** — URLs are user-navigated conversation
   pages / Places top sites, not attacker-controlled. No privilege escalation.
6. **Agentic tools (`Tools.sys.mjs`) driving privileged actions on injected web
   content** — the tool layer enforces an http/https allowlist (`isAllowedURL`) and a
   deliberate prompt-injection exfiltration guard (`isContentAllowed` gates model-chosen
   fetches on `securityProperties.untrustedInput && privateData`). Security-aware; no
   obvious hole by inspection.
7. **`SmartFormFillReviewParent`** — asserts sender `remoteType === privilegedabout`
   and validates action types against an allowlist before delegating. Well-guarded.

**Status:** No sec-high confirmed. The Smart Window's privileged surfaces are
consistently defended; the one real defect found is the sec-moderate SSRF (finding 01).
A genuine sec-high here would most likely require (a) fuzzing the media/parser stack
(memory corruption — needs a fuzzing harness, not static review), or (b) a live
prompt-injection harness driving the agent tools against a malicious page to probe the
`untrustedInput`/`privateData` taint logic for a bypass. Both are multi-session efforts.
