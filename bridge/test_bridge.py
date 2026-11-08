import json, os, stat, tempfile, threading, unittest, urllib.error, urllib.request
from http.server import ThreadingHTTPServer
import rw_bridge

TOKEN = "t" * 20


def call(port, path, token=TOKEN, body=None, method="POST"):
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data=json.dumps(body).encode() if body is not None else None, method=method)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())


class BridgeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        d = tempfile.mkdtemp()
        cls.fake = os.path.join(d, "fakeclaude")
        with open(cls.fake, "w") as f:
            f.write('#!/bin/sh\necho "echo: $2 [$4]"\n')
        os.chmod(cls.fake, os.stat(cls.fake).st_mode | stat.S_IEXEC)
        cls.srv = ThreadingHTTPServer(("127.0.0.1", 0), rw_bridge.make_handler(TOKEN, cls.fake, None, False, 10))
        cls.port = cls.srv.server_address[1]
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()

    def test_ok_and_plan_mode(self):
        c, r = call(self.port, "/ask", body={"task": "привет"})
        self.assertEqual(c, 200)
        self.assertEqual(r["answer"], "echo: привет [plan]")

    def test_auth(self):
        self.assertEqual(call(self.port, "/ask", token=None, body={"task": "x"})[0], 401)
        self.assertEqual(call(self.port, "/ask", token="wrong", body={"task": "x"})[0], 401)

    def test_validation(self):
        self.assertEqual(call(self.port, "/ask", body={"task": "  "})[0], 400)
        self.assertEqual(call(self.port, "/ask", body={"task": "x" * 9000})[0], 413)
        self.assertEqual(call(self.port, "/nope", body={"task": "x"})[0], 404)
        self.assertEqual(call(self.port, "/health", method="GET")[0], 200)

    def test_missing_command(self):
        code, out = rw_bridge.run_claude("x", "/no/such/cmd", None, False, 5)
        self.assertEqual(code, 500)

    def test_edits_flag(self):
        code, out = rw_bridge.run_claude("x", self.fake, None, True, 5)
        self.assertIn("acceptEdits", out)


if __name__ == "__main__":
    unittest.main()
