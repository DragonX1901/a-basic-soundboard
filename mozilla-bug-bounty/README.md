# Mozilla Bug Bounty — Researcher Starting Kit

A practical, rules-first guide to earning money from Mozilla's Security Bug
Bounty **without getting your account disabled or your reports rejected**.
Everything below was checked against Mozilla's live program pages in
September 2026 (see [Sources](#sources)). Read the rules section before you
touch any code — the fastest way to *lose* eligibility is to skip it.

> **This is legitimate security research, not "cyber"/hacking.** Mozilla runs
> an official, invited program. Working within its published guidelines is
> explicitly *authorized* under the Computer Fraud and Abuse Act via their
> [Safe Harbor](#safe-harbor) clause. You report privately to Mozilla, they
> fix it, you get paid, and you can be publicly credited. Nobody attacks live
> users or other people's data — you test your own local build.

---

## 1. The honest expectation-setting (read this first)

Firefox bug bounty is one of the **harder** bounty targets in existence, not
one of the easier ones. Be realistic:

- Payouts require a bug Mozilla rates **`sec-high` or `sec-critical`**. Roughly
  that means *arbitrary code execution* or *theft of high-value data*
  (passwords, credit-card numbers). Cosmetic bugs, most spoofing, and **all
  denial-of-service bugs are explicitly NOT eligible.**
- Firefox is fuzzed continuously by Mozilla's own infrastructure. Mozilla
  reserves a **7-day window** where an internal automated tool that finds the
  same bug beats you to it, and a **48-hour collision window** between external
  reporters. Low-hanging memory bugs are usually already found internally.
- The realistic path for a newcomer is **not** "read the code until I spot a
  vuln." It is **build the target, run a fuzzer or focused manual testing on a
  specific attack surface, triage the crashes, and write up the one that has a
  real security primitive.**
- A single afternoon almost never produces a payable bug. Treat this as a
  multi-week project on **one** narrow surface you actually understand.

**Where the money realistically is (2026 payout table):**

| Impact         | Up to      | Examples                                                                 |
|----------------|-----------|--------------------------------------------------------------------------|
| Highest        | $20,000   | Sandbox escape (arbitrary code in parent process); WebExtension install-prompt bypass |
| Higher         | $10,000   | UXSS (JS execution in an arbitrary cross-origin context)                 |
| High           | $3,000    | `sec-high` bugs in the threat model; GPU-process memory corruption; parent→content info disclosure via IPC |
| Mitigation bypass | $3,000 base | CSP bypass, HTML-sanitizer bypass, `eval()` in a parent-process location, etc. **+50% bonus if achieved without privileged access** |

> **2026 change to know:** As of **Feb 24, 2026**, vulnerabilities in code that
> runs in the **GPU process no longer qualify for the *sandbox escape* bounty**
> (GPU memory corruption is still `High`/$3,000). Don't file a GPU-process bug
> claiming a $20k sandbox escape.

---

## 2. Rules that get people DISQUALIFIED (memorize these)

These are the ones that actually cost people money and accounts:

1. **Too many `Invalid` submissions.** Bugzilla auto-disables accounts that file
   too many bugs that get marked Invalid, and — critically — **too many invalid
   submissions can reduce the payout on your *valid* bugs too.** Quality over
   quantity. If unsure, ask Mozilla for feedback rather than spraying reports.
2. **DoS / crash-without-a-security-primitive.** A plain crash (null-deref,
   `MOZ_CRASH`, OOM, hang) is **not** eligible. You must show memory
   *corruption* with a security consequence or another restricted-action bypass.
3. **`about:config` / non-default prefs.** A bug that only triggers under a
   pref you can only set via `about:config` or OS-level config is **not a
   supported configuration** and usually pays nothing. Prefs exposed on the
   normal Preferences UI, or on by default in Nightly/Beta, *are* in scope.
4. **Third-party code / patch-gap.** Bugs in a vendored third-party library
   that just needs updating, or "you're N versions behind upstream," are **not
   paid**. It must be Mozilla's own code (or a genuine vuln in vendored code
   that ships in the client).
5. **End-of-life products & old "Fennec" Android app.** Out of scope. In scope:
   Firefox / Firefox ESR (desktop), the new Firefox for Android (URL bar at the
   bottom), Firefox for iOS — including Nightly and Beta.
6. **Bugzilla, Rust, Rhino, and other project tooling.** Explicitly **out of
   scope** for the client bounty. Only end-user products count.
7. **Setting severity keywords / CVSS yourself, or dumping huge output in the
   Description.** Don't. Attach PoCs/logs as **attachments**, describe **one
   issue per bug**, and let the committee rate it.
8. **Publishing / extortion / touching real user data.** Don't disclose before
   Mozilla has had reasonable time, never threaten to withhold or leak, and use
   **test accounts** — never real user data. Any of these voids the bounty.
9. **Sanctions / employment.** Must not be on a US sanctions list or in a
   sanctioned country; must not be a Mozilla employee/contractor; must not have
   written or reviewed the buggy code.

---

## 3. A payable report needs, at minimum

Per Mozilla's Report Criteria, a submission must contain:

- **Enough to diagnose and fix** the vulnerability.
- **A simple, reproducible test case.**
- **At least one of:**
  - an **ASAN stacktrace or crash dump** (for memory corruption), **or**
  - a **root-cause analysis** of where/why the bug occurs.

You do *not* need a full weaponized exploit. Improving the test case and
helping engineers during triage can *increase* your payout.

---

## 4. How to file (do it this exact way)

1. Create a **Bugzilla** account: <https://bugzilla.mozilla.org/>
2. File through the dedicated form so it's auto-flagged for the bounty:
   **<https://bugzilla.mozilla.org/form.client.bounty>**
3. **One security issue per submission.** Concise Description; attach PoC,
   repro, ASAN log, etc. as separate **attachments** (attach one, submit, then
   add the rest — avoid zip bundles except for related file sets).
4. If you filed directly in Bugzilla *without* the form, immediately email
   **security@mozilla.org** with the bug number and say you're claiming the
   bounty. **Never email the actual vulnerability details.**
5. Stay reachable to help triage — it's rewarded.
6. Bounty-status questions go to **security@mozilla.org**, *not* Bugzilla
   comments.

---

## 5. Picking a target surface (concrete options)

Don't "debug Firefox" in the abstract. Pick ONE surface, learn it deeply:

- **Fuzzing an isolated parser/decoder.** Media (audio/video/image) demuxers,
  font shaping, the URL parser, WebGL/ANGLE, the JS engine. Use Mozilla's own
  fuzzing harness — see the "libFuzzer" and "fuzzing interface" docs. This is
  the highest-yield path for memory-corruption bounties.
- **Exploit-mitigation bypasses** (their own dedicated bounty, +50% bonus if
  unprivileged). Each has a *precise* definition on the client page. Examples:
  - **HTML Sanitizer bypass** — find a privileged sink that takes
    attacker-controlled data and *isn't* sanitized, or a payload that survives
    the sanitizer and can execute JS in Firefox chrome.
  - **`eval()` in the parent / System Principal** — find a still-reachable
    `eval` location that isn't on the explicit allowlist (the classic example
    was one that "forgot to perform the checks on **workers**").
  - **CSP-on-`about:`-pages bypass**, **javascript: URL in chrome**,
    **parent-process load restrictions**, **Xray wrapper confusion**, etc.
- **UXSS** — execute JS in an *arbitrary* cross-origin context. High bar
  (limited/complex-interaction variants pay less) but $10k+ tier.

See [`recon-notes.md`](./recon-notes.md) for a worked example of how to reason
about one of these surfaces (the HTML-fragment / Sanitizer mitigation) using
the actual current source — including why the obvious "gotchas" there are
**not** bugs, so you don't waste a submission on them.

---

## 6. Building & instrumenting Firefox (the actual work)

You're on Zorin (Debian-based), which is well supported.

```bash
# 1. Bootstrap the toolchain and get the tree (uses Mercurial or the Git mirror)
curl https://raw.githubusercontent.com/mozilla-firefox/firefox/main/python/mozboot/bin/bootstrap.py -O
python3 bootstrap.py        # choose "Firefox for Desktop"; installs deps

# 2. For memory-safety work, build with AddressSanitizer + a debug/fuzz config.
#    Create mozconfig:
cat > mozconfig <<'EOF'
ac_add_options --enable-address-sanitizer
ac_add_options --disable-jemalloc
ac_add_options --enable-fuzzing
ac_add_options --enable-debug
EOF

./mach build
./mach run                  # sanity-check the browser launches
```

- **ASAN build** is what produces the stacktraces Mozilla wants in a report.
- **`--enable-fuzzing`** enables the libFuzzer targets and the JS/DOM fuzzing
  interface. Run `./mach fuzzing` targets, or drive with the fuzzing framework.
- Consider Mozilla's **`fuzzfetch`** to just download prebuilt ASAN/fuzzing
  nightlies instead of compiling for hours: `pip install fuzzfetch`.
- Triage crashes with **`./mach test`**, `rr` (record/replay), and the ASAN
  report. A clean, minimized repro dramatically raises your bounty odds.

Key official docs to bookmark:
- Firefox Source Docs → *Fuzzing* and *Building Firefox*.
- Bug-writing guidelines & security-bug handling policy (linked from the
  program pages).
- **Searchfox** (<https://searchfox.org/>) to read/navigate the source fast.

---

## 7. Suggested first-week plan

1. Read the client bounty + FAQ pages end to end (links below). Re-read §2 here.
2. `fuzzfetch` an ASAN fuzzing nightly (skip the multi-hour build for now).
3. Pick **one** libFuzzer target for a parser you find interesting.
4. Let it run; collect crashes; minimize; get ASAN stacks.
5. For each crash, ask: *is this memory corruption with a security primitive, or
   just a DoS/assert?* Discard the DoS ones (not eligible).
6. If you get a corruption with a plausible security impact, write the report
   per §3/§4 and file via the bounty form.
7. In parallel, study **one** mitigation from §5 deeply — those bypasses are
   often reasoning bugs rather than fuzzing bugs, and carry the +50% bonus.

---

## Safe Harbor

Mozilla will not pursue or threaten legal action (including DMCA) against
good-faith research that complies with the program, considers it *authorized*
under the CFAA, and waives conflicting ToS/AUP restrictions for that research.
They can only authorize testing of **Mozilla's own** systems — not
interconnected third-party services. If in doubt, ask
**security@mozilla.org** first.

## Sources

- Security Bug Bounty (overview + general eligibility + safe harbor):
  <https://www.mozilla.org/en-US/security/bug-bounty/>
- Client Bug Bounty (scope, payout table, mitigation list, filing):
  <https://www.mozilla.org/en-US/security/client-bug-bounty/>
- Bug Bounty FAQ (eligible software, DoS, spoofing, GPU-process 2026 change):
  <https://www.mozilla.org/en-US/security/bug-bounty/faq/>
- Web & Services Bug Bounty (if you'd rather target Mozilla websites):
  <https://www.mozilla.org/en-US/security/web-bug-bounty/>
- File a client bounty bug: <https://bugzilla.mozilla.org/form.client.bounty>
- Source navigation: <https://searchfox.org/>

*Program details change — always confirm against the live pages above before
you file.*
