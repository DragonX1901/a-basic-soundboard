from marionette_driver.marionette import Marionette
m = Marionette(host="127.0.0.1", port=2828); m.start_session(); m.set_context("chrome")
m.timeout.script = 60
script = r"""
const url = arguments[0];
const cb = arguments[arguments.length - 1];
const { captureThumbnail } = ChromeUtils.importESModule(
  "moz-src:///browser/components/aiwindow/models/HistoryThumbnails.sys.mjs");
captureThumbnail(url).then(r => cb(JSON.stringify({url, result: r})),
                          e => cb(JSON.stringify({url, error: String(e)})));
"""
import sys
url = sys.argv[1]
print(m.execute_async_script(script, script_args=[url]))
m.delete_session()
