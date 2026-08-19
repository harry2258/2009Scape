"""Mock of the game server's telemetry API (telemetryService.md) for dashboard dev.

Serves canned-but-mutable JSON for every endpoint on 127.0.0.1:8456 so
bot_ui.html can be developed and demoed without booting the world. Spawn,
delete, and the chaos hooks mutate the in-memory bot list; a background thread
advances ticks and emits occasional STATE/TELEPORT events so live feeds move.

Run:  python Tools/telemetry_mock.py [port]      (default 8456)
"""

import json
import random
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

START_TIME = time.time()
LOCK = threading.Lock()
TICK = [0]
NEXT_SEQ = {}


def bot(name, script, x, y, z=0, hp=100, stuck=False, afk=False):
    return {
        "name": name, "script": script, "location": {"x": x, "y": y, "z": z},
        "hp_percent": hp, "stuck": stuck, "afk": afk,
        "state_age": random.randint(0, 40), "tile_age": random.randint(0, 5),
        "events": [],
    }


BOTS = [
    bot("Kermit42", "Adventurer", 3180, 3433),
    bot("Miss Piggy7", "Adventurer", 3164, 3485, hp=64),
    bot("Gonzo13", "CoalMiner", 3032, 9762, z=0, stuck=True),
    bot("Rowlf88", "LobsterCatcher", 2836, 3431),
    bot("Fozzie3", "AFK", 3222, 3217, afk=True),
]
SCRIPTS = [
    {"name": "Adventurer", "package": "content.global.bots", "spawnable": False,
     "states": ["START", "ADVENTURE", "WALKING_PATH", "GE", "RECOVER_DEATH", "RECOVER_BANK"],
     "constructors": ["CombatStyle"]},
    {"name": "CoalMiner", "package": "content.global.bots", "spawnable": True,
     "states": ["MINING", "BANKING", "WALKING"], "constructors": []},
    {"name": "LobsterCatcher", "package": "content.global.bots", "spawnable": True,
     "states": ["FISHING", "BANKING"], "constructors": []},
    {"name": "WildernessPKer", "package": "content.global.bots", "spawnable": True,
     "states": [], "constructors": []},
]
ITEMS = {385: ("Shark", 720), 995: ("Coins", 1), 453: ("Coal", 173), 377: ("Raw lobster", 190)}


def emit(bot_, type_, detail):
    bot_["events"].append({"seq": NEXT_SEQ.get(bot_["name"], 0) + 1,
                           "tick": TICK[0], "type": type_, "detail": detail})
    NEXT_SEQ[bot_["name"]] = bot_["events"][-1]["seq"]
    del bot_["events"][:-100]


def sim_thread():
    while True:
        time.sleep(0.6)
        with LOCK:
            TICK[0] += 1
            for b in BOTS:
                b["state_age"] += 1
                b["tile_age"] += 1
                if b["afk"]:
                    continue  # AFK bodies are untracked, matching the real server
                if b["script"] == "Adventurer" and random.random() < 0.08:
                    b["state_age"], b["tile_age"] = 0, 0
                    emit(b, "STATE", random.choice(
                        ["ADVENTURE", "FIND_BANK", "LOOT", "WALKING_PATH"]))
                if random.random() < 0.02:
                    emit(b, "TELEPORT",
                         "(%d, %d, 0) -> (%d, %d, 0)" % (b["location"]["x"], b["location"]["y"],
                                                         random.randint(3100, 3300),
                                                         random.randint(3300, 3500)))


def bot_summary(b):
    return {"name": b["name"], "script": b["script"], "location": b["location"],
            "hp_percent": b["hp_percent"],
            "ticks_since_state_change": -1 if b["afk"] else b["state_age"],
            "ticks_since_tile_change": -1 if b["afk"] else b["tile_age"],
            "stuck": b["stuck"]}


def bot_detail(b):
    inv = [{"id": i, "amount": random.randint(1, 20), "name": n, "value": v}
           for i, (n, v) in random.sample(list(ITEMS.items()), 2)]
    return {**bot_summary(b), "type": b["script"],
            "current_goal": "AFK (no script)" if b["afk"] else
            "%s [personality=MERCHANT, city=(3164, 3485, 0)]" % b["script"].upper(),
            "combat_target": None, "interacting_with": None,
            "pathing": {"destination": None if b["stuck"] else
                        {"x": b["location"]["x"] + 5, "y": b["location"]["y"] + 3, "z": 0},
                        "queue_size": 0 if b["stuck"] else 4},
            "xp_per_hour": 0.0 if b["afk"] else 12450.0,
            "inventory": inv, "inventory_value": sum(i["value"] * i["amount"] for i in inv),
            "equipment": [], "ge_offer": None, "ground_items_held": 0}


def find(name):
    return next((b for b in BOTS if b["name"].lower() == name.lower()), None)


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _body(self):
        length = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(length)) if length else {}

    def do_GET(self):
        path = self.path.split("?")[0].rstrip("/")
        query = dict(re.findall(r"(\w+)=([^&]*)", self.path))
        with LOCK:
            if path == "/api/server":
                self._send(200, {
                    "uptime_ms": int((time.time() - START_TIME) * 1000), "ticks": TICK[0],
                    "last_cycle_duration_ms": 180 + random.randint(-40, 90),
                    "players_online": 3, "bots_online": len(BOTS), "world_id": 1,
                    "jvm": {"used_memory_mb": 812, "total_memory_mb": 2048,
                            "max_memory_mb": 4096, "thread_count": 42}})
            elif path == "/api/server/performance":
                self._send(200, {"bot_script_cap": 120, "smoothed_cycle_time_ms": 215.5,
                                 "bot_pulses_triggered_this_tick": 12,
                                 "registered_scripted_bots": len(BOTS) - 1})
            elif path == "/api/scripts":
                self._send(200, {"scripts": SCRIPTS})
            elif path == "/api/bots":
                bots = [bot_summary(b) for b in BOTS
                        if not (query.get("stuck", "").lower() == "true" and not b["stuck"])]
                by_script = {}
                for b in BOTS:
                    by_script[b["script"]] = by_script.get(b["script"], 0) + 1
                self._send(200, {"total": len(BOTS), "by_script": by_script, "bots": bots})
            else:
                m = re.fullmatch(r"/api/bots/([^/]+)/events", path)
                if m:
                    b = find(m.group(1))
                    if not b:
                        self._send(404, {"error": "No bot named '%s'" % m.group(1)})
                    elif b["afk"]:
                        self._send(404, {"error": "No tracking data for '%s'" % b["name"]})
                    else:
                        since = int(query.get("since", 0) or 0)
                        events = [e for e in b["events"] if e["seq"] > since]
                        self._send(200, {"events": events,
                                         "next_cursor": events[-1]["seq"] if events else since})
                    return
                m = re.fullmatch(r"/api/bots/([^/]+)", path)
                if m:
                    b = find(m.group(1))
                    self._send(200, bot_detail(b)) if b else \
                        self._send(404, {"error": "No bot named '%s'" % m.group(1)})
                else:
                    self._send(404, {"error": "Not found"})

    def do_POST(self):
        path = self.path.rstrip("/")
        body = self._body()
        with LOCK:
            if path == "/api/bots/spawn":
                script = body.get("type", "Adventurer")
                name = script + str(random.randint(10, 99))
                BOTS.append(bot(name, script, 3222 + random.randint(-5, 5), 3217))
                emit(BOTS[-1], "SPAWN", "%s at (3222, 3217, 0)" % script)
                self._send(201, {"name": name})
                return
            m = re.fullmatch(r"/api/bots/([^/]+)/test", path)
            if m:
                b = find(m.group(1))
                if not b:
                    self._send(404, {"error": "No bot named '%s'" % m.group(1)})
                    return
                action = body.get("action", "")
                if action == "kill":
                    b["hp_percent"] = 100
                    b["location"] = {"x": 3222, "y": 3217, "z": 0}
                    emit(b, "DEATH", "died at (%d, %d, 0)" % (b["location"]["x"], b["location"]["y"]))
                    self._send(200, {"result": "death started for %s" % b["name"]})
                elif action == "teleport":
                    loc = body.get("location", {})
                    b["location"] = {"x": loc.get("x", 3222), "y": loc.get("y", 3217),
                                     "z": loc.get("z", 0)}
                    emit(b, "TELEPORT", "(0, 0, 0) -> (%d, %d, %d)" %
                         (b["location"]["x"], b["location"]["y"], b["location"]["z"]))
                    self._send(200, {"result": "teleported %s" % b["name"]})
                elif action == "give_item":
                    self._send(200, {"result": "gave item %s" % body.get("item_id")})
                elif action == "clear_inventory":
                    self._send(200, {"result": "cleared inventory"})
                else:
                    self._send(400, {"error": "Unknown action '%s'" % action})
                return
            self._send(404, {"error": "Not found"})

    def do_DELETE(self):
        m = re.fullmatch(r"/api/bots/([^/]+)", self.path.rstrip("/"))
        with LOCK:
            b = find(m.group(1)) if m else None
            if not b:
                self._send(404, {"error": "No bot named '%s'" % (m.group(1) if m else "?")})
            else:
                BOTS.remove(b)
                self._send(200, {"deleted": b["name"]})

    def log_message(self, *args):
        pass


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8456
    threading.Thread(target=sim_thread, daemon=True).start()
    print("Mock telemetry API on http://127.0.0.1:%d (Ctrl+C to stop)" % port)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
