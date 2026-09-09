from marionette_driver.marionette import Marionette
import sys
m = Marionette(host="127.0.0.1", port=2828); m.start_session()
# CONTENT context: run as page script in the about:aichatcontent document
m.set_context("content")
m.timeout.page_load = 30
try:
    m.navigate("about:aichatcontent")
    print("NAV_OK url=", m.get_url())
except Exception as e:
    print("NAV_ERR", e); 
uniq = sys.argv[1]
# Dispatch the real DOM event the child actor forwards to the parent.
# wantUntrusted:true => untrusted page-script event is honored.
script = r"""
const uniq = arguments[0];
const detail = {
  conversationId: "poc-e2e",
  messageId: "poc-e2e",
  items: [{ url: "http://127.0.0.1:8899/ignored",
            thumbnail: "http://127.0.0.1:8899/" + uniq }],
};
const target = document.querySelector("ai-chat-content") || document.body || document.documentElement;
const ev = new CustomEvent("AIChatContent:RequestAssets", { detail, bubbles: true, composed: true });
target.dispatchEvent(ev);
return { dispatchedOn: target && target.localName, url: document.location.href,
         hasChatContent: !!document.querySelector("ai-chat-content") };
"""
print("DISPATCH_RESULT:", m.execute_script(script, script_args=[uniq]))
m.delete_session()
