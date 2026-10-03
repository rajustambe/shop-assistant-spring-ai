"""
Proper end-to-end MCP test: connects to the shop-assistant MCP server over SSE,
lists the tools, and CALLS getOrderStatus(1001) — all over the MCP protocol, no LLM.

Usage:
    python mcp_test.py                 # tests http://localhost:8080
    python mcp_test.py 8089            # tests a different port
"""
import json, sys, threading, queue, time, urllib.request

PORT = sys.argv[1] if len(sys.argv) > 1 else "8080"
BASE = f"http://localhost:{PORT}"

inbox = queue.Queue()
msg_url = [None]

def sse_reader():
    resp = urllib.request.urlopen(BASE + "/sse")
    event = None
    for raw in resp:
        line = raw.decode("utf-8").rstrip("\n").rstrip("\r")
        if line.startswith("event:"):
            event = line[6:].strip()
        elif line.startswith("data:"):
            data = line[5:].strip()
            if event == "endpoint":
                msg_url[0] = BASE + data
            elif event == "message":
                inbox.put(data)
        elif line == "":
            event = None

threading.Thread(target=sse_reader, daemon=True).start()

# wait for the server to hand us the message endpoint (the handshake)
t0 = time.time()
while msg_url[0] is None:
    if time.time() - t0 > 10:
        print("ERROR: no SSE endpoint event — is the app running on", BASE, "?"); sys.exit(1)
    time.sleep(0.05)
print("connected. message endpoint =", msg_url[0])

def post(obj):
    req = urllib.request.Request(msg_url[0], data=json.dumps(obj).encode(),
                                 headers={"Content-Type": "application/json"})
    urllib.request.urlopen(req).read()

def rpc(method, params, rid):
    post({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
    t0 = time.time()
    while time.time() - t0 < 15:
        obj = json.loads(inbox.get())
        if obj.get("id") == rid:
            return obj
    raise TimeoutError(f"no response to {method}")

# 1) initialize handshake
rpc("initialize", {"protocolVersion": "2024-11-05", "capabilities": {},
                   "clientInfo": {"name": "mcp_test", "version": "1"}}, 1)
post({"jsonrpc": "2.0", "method": "notifications/initialized"})

# 2) list the tools the server exposes
tools = rpc("tools/list", {}, 2)
print("\nTOOLS EXPOSED OVER MCP:")
for t in tools["result"]["tools"]:
    print("  -", t["name"], ":", t.get("description", "")[:70])

# 3) actually CALL a tool over MCP
call = rpc("tools/call", {"name": "getOrderStatus", "arguments": {"orderId": 1001}}, 3)
print("\nCALL getOrderStatus(1001) ->")
print("  ", call["result"]["content"][0]["text"])
