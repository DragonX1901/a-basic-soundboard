# Finding 01 — Content→parent SSRF / info-disclosure via unvalidated thumbnail URL in Firefox "Smart Window" (AI window)

**Status:** Candidate for Mozilla Client Bug Bounty. Static-analysis finding with a
fully traced call chain; not yet reproduced in a build (PoC steps below).
**Component:** `browser/components/aiwindow` (Smart Window / AI window), thumbnail service.
**Class:** Content-process → parent-process SSRF + cross-process information-disclosure
(unvalidated URL reaches a system-principal load).
**Estimated rating:** sec-moderate → sec-high. Maps to Mozilla's *"Information
disclosure from the parent to a web content process"* (High Impact, $3,000) and an
SSRF primitive. Final rating depends on the committee's weighting of the
`privilegedabout` precondition (see Preconditions).
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

A compromised `privilegedabout` content process can therefore make the **parent
process** fetch arbitrary URLs (SSRF, including `file://`, `http://localhost`,
intranet hosts, `resource://`, `chrome://`, `about:`), and learn — via whether a
non-empty thumbnail comes back — the existence/renderability of local files and
internal resources it otherwise cannot reach. Every *other* URL entry point in the
same feature enforces `http:`/`https:`; this one is the exception.

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

From a compromised `privilegedabout` content process:

1. **Parent-process SSRF.** Force the parent to issue GET requests to any URL,
   including `http://localhost`/intranet endpoints unreachable from a content
   process, with the parent's network position. (`LOAD_ANONYMOUS`, so no cookies —
   still reaches internal services.)
2. **Cross-process existence / renderability oracle.** Point `thumbnail` at
   `file:///path/to/image.png` (or `resource://`, `chrome://`, `about:` internal
   pages). A non-empty result (`image` is a `moz-page-thumb://` URL vs `null`)
   discloses whether the target exists and renders — information the sandboxed
   content process cannot otherwise obtain. A rendered thumbnail of the resource is
   generated into the content-reachable `moz-page-thumb://` store.

The clean, guaranteed primitives are SSRF and the existence/renderability oracle.
Full pixel readback of the resulting thumbnail from the content side may be limited
by canvas tainting (not required for the finding, and not claimed here).

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

## PoC steps to confirm in a build (ASAN/debug Nightly)

1. Build/run with `browser.smartwindow.enabled=true` (and, if needed,
   `--enable-address-sanitizer`), or use a Nightly where the feature is on.
2. Open the Smart Window so `about:aichatcontent` is live.
3. In that document's process (simulating a compromised content process — e.g. via
   the Browser Toolbox attached to the `privilegedabout` frame, or a content-process
   JS injection harness), obtain the `AIChatContent` child actor and send:
   ```js
   actor.sendAsyncMessage("AIChatContent:RequestAssets", {
     conversationId: "poc", messageId: "poc",
     items: [{ url: "http://x/", thumbnail: "file:///etc/hostname" },
             { url: "http://x/", thumbnail: "file:///path/to/known-image.png" },
             { url: "http://x/", thumbnail: "http://127.0.0.1:PORT/internal" }],
   });
   ```
4. Observe: the parent background-thumbnail browser issues the loads (instrument
   `BackgroundThumbnailsChild` `Browser:Thumbnail:LoadURL`, or a local HTTP listener
   on 127.0.0.1 to see the SSRF hit); and the `AIChatContent:AssetsReady` reply
   carries a non-null `image` (`moz-page-thumb://…`) for renderable targets vs `null`
   otherwise — the disclosure oracle.
5. Capture the exact loads and the system-principal triggering principal for the
   report (a `root cause analysis` per Mozilla's report criteria; an ASAN stack is
   not applicable to a logic bug).

---

## Filing notes

- File via <https://bugzilla.mozilla.org/form.client.bounty>, **one issue**, with
  this analysis as the description and the PoC output as an **attachment**.
- Do **not** email the vulnerability. If filed directly in Bugzilla, email
  `security@mozilla.org` with only the bug number to claim the bounty.
- Be upfront about the `privilegedabout` precondition and whether the feature is
  enabled in the tested channel — accurate scoping protects your reporter
  reputation and avoids an `Invalid` mark.

*This is a code-review finding with a complete call-chain trace, not a confirmed
live exploit. Reproduce in a build before filing.*
