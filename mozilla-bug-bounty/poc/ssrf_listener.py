import http.server, socketserver, sys, datetime
PNG = open("/home/user/poc/px.png","rb").read()
class H(http.server.BaseHTTPRequestHandler):
    def _log(self):
        line = f"{datetime.datetime.now().isoformat()} {self.command} {self.path} from {self.client_address[0]}"
        print("SSRF-HIT:", line, flush=True)
        open("/home/user/poc/ssrf_hits.log","a").write(line+"\n")
    def do_GET(self):
        self._log()
        self.send_response(200); self.send_header("Content-Type","image/png")
        self.send_header("Content-Length",str(len(PNG))); self.end_headers(); self.wfile.write(PNG)
    def log_message(self,*a): pass
if __name__=="__main__":
    port=int(sys.argv[1]) if len(sys.argv)>1 else 8899
    socketserver.TCPServer.allow_reuse_address=True
    with socketserver.TCPServer(("127.0.0.1",port),H) as s:
        print(f"listening on 127.0.0.1:{port}",flush=True); s.serve_forever()
