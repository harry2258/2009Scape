"""Numeric verification of the world-map overlay alignment.

Samples the canvas pixel at each visible bot's plotted position and compares
it with map.webp's color at the transform-predicted source pixel. Pure math,
no vision involved.
"""

import json
import urllib.request

from playwright.sync_api import sync_playwright

CX, BX, CY, BY = 1.96378, -3346.81, -2.04242, 8569.48


def webp_pixel(px, py):
    from PIL import Image
    img = Image.open("map.webp").convert("RGB")
    x, y = int(round(px)), int(round(py))
    x = max(0, min(img.width - 1, x))
    y = max(0, min(img.height - 1, y))
    return img.getpixel((x, y))


def main():
    with urllib.request.urlopen("http://127.0.0.1:8456/api/bots", timeout=10) as r:
        bots = {b["name"]: b for b in json.load(r)["bots"]}
    targets = ["DraynorFisher", "CatherbyFisher", "AlKharidMiner", "CowKiller"]
    picked = {}
    for name, b in bots.items():
        if b["script"] in targets and b["script"] not in picked:
            picked[b["script"]] = name
    print("probe bots:", picked)

    with sync_playwright() as p:
        browser = p.chromium.launch(channel="chrome", headless=True)
        page = browser.new_page(viewport={"width": 1600, "height": 1000})
        errors = []
        page.on("pageerror", lambda e: errors.append(str(e)))
        page.goto("http://127.0.0.1:8789/bot_ui.html")
        page.wait_for_timeout(7000)

        info = page.evaluate(
            """() => {
              const imgOk = mapState.img && mapState.img.complete && mapState.img.naturalWidth;
              const probes = [];
              const seen = new Set();
              for (const hit of state.mapHits) {
                if (['DraynorFisher','CatherbyFisher','AlKharidMiner','CowKiller'].includes(hit.bot.script)
                    && !seen.has(hit.bot.script)) {
                  seen.add(hit.bot.script);
                  const c = document.getElementById('bot-map');
                  const ctx = c.getContext('2d');
                  // sample just beside the dot so we read the map, not the marker
                  const d = ctx.getImageData(Math.round(hit.sx + 9), Math.round(hit.sy), 1, 1).data;
                  probes.push({name: hit.bot.name, script: hit.bot.script, sx: hit.sx, sy: hit.sy,
                               rgba: [d[0], d[1], d[2]]});
                  if (probes.length >= 4) break;
                }
              }
              return {imgOk, mode: mapState.mode, probes};
            }"""
        )
        browser.close()

    print("map image loaded:", info["imgOk"], "| mode:", info["mode"])
    print(f"{'script':16s} {'canvas rgba':16s} {'webp rgb @ predicted':22s} color-dist")
    worst = 0
    for probe in info["probes"]:
        b = bots[probe["name"]]
        ex = CX * b["location"]["x"] + BX
        ey = CY * b["location"]["y"] + BY
        wr, wg, wb = webp_pixel(ex, ey)
        cr, cg, cb = probe["rgba"]
        dist = abs(cr - wr) + abs(cg - wg) + abs(cb - wb)
        worst = max(worst, dist)
        print(f"{b['script']:16s} ({cr:3d},{cg:3d},{cb:3d})    ({wr:3d},{wg:3d},{wb:3d})@({ex:.0f},{ey:.0f})  {dist}")
    print("page errors:", errors or "none")
    print("WORST COLOR DISTANCE:", worst, "(< ~90 = overlay aligned)")


if __name__ == "__main__":
    main()
