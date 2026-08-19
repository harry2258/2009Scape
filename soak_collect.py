"""Soak-test collector: snapshots bot telemetry every 10 min for an hour.

Appends one JSON line per sample to telemetry_soak.jsonl next to this file.
Scratch tooling for the fight-dynamics tuning round - not part of the server.
"""
import datetime
import json
import os
import statistics
import time
import urllib.request

BASE = "http://127.0.0.1:8456"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "telemetry_soak.jsonl")
SAMPLES = 7          # t=0,10,20,30,40,50,60 minutes
INTERVAL = 600


def get(path, tries=6):
    last = None
    for _ in range(tries):
        try:
            with urllib.request.urlopen(BASE + path, timeout=25) as r:
                return json.load(r)
        except Exception as e:  # noqa: BLE001 - retry any fetch failure
            last = e
            time.sleep(3)
    raise last


def bots_summary():
    try:
        b = get("/api/bots")
    except Exception as e:  # noqa: BLE001 - big payload times out sometimes
        return {"error": repr(e)}
    bots = b["bots"] if isinstance(b, dict) else b
    pk = [x for x in bots if x.get("script") == "WildernessPKer"]
    north = [x for x in pk if (x.get("location") or {}).get("y", 0) > 3520]
    depths = [(x["location"]["y"] - 3520) // 8 + 1 for x in north]
    return {
        "pker_alive": len(pk),
        "pker_stuck": sum(1 for x in pk if x.get("stuck")),
        "pker_lowhp": sum(1 for x in pk if x.get("hp_percent", 100) < 50),
        "pker_north": len(north),
        "depth_median": int(statistics.median(depths)) if depths else 0,
        "depth_max": max(depths) if depths else 0,
    }


def sample():
    t = get("/api/server/techniques")
    d = get("/api/server/deaths")
    rec = {
        "ts": datetime.datetime.now().isoformat(timespec="seconds"),
        "tech_totals": t.get("totals", {}),
        "tech_by_build": t.get("by_build", {}),
        "deaths_total": d.get("total_bot_deaths", 0),
        "deaths_by_killer": d.get("by_killer_type", {}),
        "deaths_by_script": d.get("by_script", {}),
        "wild_bands": d.get("by_wilderness_band", {}),
        "loot_value": d.get("loot", {}).get("total_value", 0),
        "loot_items": d.get("loot", {}).get("items_dropped", 0),
    }
    rec.update(bots_summary())
    return rec


def main():
    with open(OUT, "a", encoding="utf-8") as f:
        for i in range(SAMPLES):
            try:
                rec = sample()
            except Exception as e:  # noqa: BLE001 - keep collecting on failure
                rec = {"ts": datetime.datetime.now().isoformat(timespec="seconds"),
                       "error": repr(e)}
            f.write(json.dumps(rec) + "\n")
            f.flush()
            print(f"sample {i + 1}/{SAMPLES} @ {rec['ts']}", flush=True)
            if i < SAMPLES - 1:
                time.sleep(INTERVAL)
    print("done")


if __name__ == "__main__":
    main()
