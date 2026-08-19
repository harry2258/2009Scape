"""Interactive verification of bot_ui.html via Playwright driving system Chrome.

Run:  python Tools/ui_smoke_test.py
Requires the mock (Tools/telemetry_mock.py 18456) + test proxy
(BOT_UI_API=http://127.0.0.1:18456 python bot_ui_server.py 18789) and the
live proxy (python bot_ui_server.py 8789).
"""

import sys
import time

from playwright.sync_api import sync_playwright

PASS, FAIL = [], []


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(f"  {'PASS' if cond else 'FAIL'}  {name}" + (f"  [{detail}]" if detail else ""))


def last_toast(page):
    return page.locator("#toasts .toast .t-title").last.text_content()


def run(page, url, label, live=False):
    print(f"\n=== {label} ({url}) ===")
    errors = []
    page.on("pageerror", lambda e: errors.append(str(e)))
    page.goto(url)
    page.wait_for_timeout(8000 if live else 3500)  # live /api/bots is slow with 1000+ bots

    # status strip populated
    check("status uptime populated", page.text_content("#st-uptime").strip() != "—",
          page.text_content("#st-uptime"))
    check("status online populated", "bots" in page.text_content("#st-online"),
          page.text_content("#st-online"))
    rows = page.locator("#bot-tbody tr")
    check("table has rows", rows.count() > 0, f"{rows.count()} rows")

    # drawer: click a row
    rows.first.click()
    # live detail polls can be slow under load — wait for content, not a fixed sleep
    name = None
    for _ in range(12 if live else 3):
        page.wait_for_timeout(700)
        name = page.text_content("#dw-name").strip()
        if name not in ("", "—"):
            break
    drawer_open = "open" in (page.get_attribute("#drawer", "class") or "")
    check("drawer opens on row click", drawer_open)
    check("drawer shows bot name", name not in ("", "—"), name)
    goal = page.text_content("#dw-goal").strip()
    check("drawer shows goal", goal not in ("", "—"), goal[:50])

    # header must stay clickable with the drawer open (content shifts left)
    page.click("#btn-refresh", timeout=4000)
    check("header clickable with drawer open", True)

    ev_count = page.locator("#event-log .ev").count()
    print(f"  info   event log entries: {ev_count}")

    if not live:
        # give item (no confirm)
        page.fill("#gi-id", "995")
        page.fill("#gi-amt", "1000")
        page.click("#ac-give")
        page.wait_for_timeout(1500)
        check("give item toast", last_toast(page) == "Give item", last_toast(page))

        # teleport (no confirm)
        page.fill("#tp-x", "3200")
        page.fill("#tp-y", "3200")
        page.click("#ac-teleport")
        page.wait_for_timeout(1500)
        check("teleport toast", last_toast(page) == "Teleport", last_toast(page))

        # kill (confirm dialog)
        page.once("dialog", lambda d: d.accept())
        page.click("#ac-kill")
        page.wait_for_timeout(1500)
        check("kill toast", last_toast(page) == "Kill", last_toast(page))

        # AFK bot: events endpoint 404s -> friendly note in the log
        page.click("#dw-close")
        page.fill("#flt-search", "fozzie")
        page.wait_for_timeout(600)
        page.locator("#bot-tbody tr").first.click()
        page.wait_for_timeout(2500)
        note = page.text_content("#event-log").strip()
        check("AFK bot shows untracked note", "only scripted bots are tracked" in note, note[:60])
        page.fill("#flt-search", "")
        page.wait_for_timeout(400)
        page.click("#dw-close")

        # spawn panel
        page.click("#btn-spawn-toggle")
        page.wait_for_timeout(500)
        check("spawn panel opens", "open" in (page.get_attribute("#spawn-panel", "class") or ""))
        opts = page.locator("#sp-script option").all_text_contents()
        check("spawn scripts listed", len(opts) > 0, f"{len(opts)} options")
        page.select_option("#sp-script", "CoalMiner")
        preview = page.text_content("#spawn-preview")
        check("spawn preview updates", '"type":"CoalMiner"' in preview, preview)
        page.click("#sp-go")
        page.wait_for_timeout(2000)
        toasts = page.locator("#toasts .toast .t-title").all_text_contents()
        check("spawn toast", any(t.startswith("Spawned") for t in toasts), str(toasts))

        # search filter narrows table
        page.fill("#flt-search", "coal")
        page.wait_for_timeout(400)
        n_filtered = page.locator("#bot-tbody tr").count()
        check("search filter narrows", 0 < n_filtered <= rows.count(), f"{n_filtered} rows")
        page.fill("#flt-search", "")
        page.wait_for_timeout(400)

        # stuck-only toggle
        page.check("#flt-stuck")
        page.wait_for_timeout(400)
        badges = page.locator("#bot-tbody .badge.stuck").count()
        check("stuck filter shows stuck rows", badges > 0, f"{badges} stuck badges")
        page.uncheck("#flt-stuck")

    # sorting: click HP header twice
    page.click('#bot-table th[data-k="hp_percent"]')
    page.wait_for_timeout(300)
    arrow = page.text_content('#bot-table th[data-k="hp_percent"] .arrow')
    check("sort arrow appears", arrow in ("▲", "▼"), arrow)

    # ---- map zoom / pan ----
    def map_note():
        return page.evaluate("mapState.view ? Math.round(mapState.view.x1 - mapState.view.x0) : null")

    # ensure a fresh auto view (view may exist from earlier interactions)
    page.click("#map-fit")
    page.wait_for_timeout(400)
    span0 = map_note()
    box = page.locator("#bot-map").bounding_box()
    cxm, cym = box["x"] + box["width"] / 2, box["y"] + box["height"] / 2
    page.mouse.move(cxm, cym)
    page.mouse.wheel(0, -600)   # zoom in
    page.wait_for_timeout(600)
    span1 = map_note()
    check("wheel zooms in", span0 is not None and span1 is not None and span1 < span0 * 0.7,
          f"{span0} -> {span1}")
    page.mouse.wheel(0, 1200)   # zoom back out
    page.wait_for_timeout(600)
    span2 = map_note()
    # zoom-out grows the span but is clamped near the whole-world span (~2280)
    check("wheel zooms out (clamped)", span2 is not None and span1 < span2 <= 2450,
          f"{span1} -> {span2}")
    # drag pan
    page.click("#map-fit")
    page.wait_for_timeout(400)
    v0 = page.evaluate("({...mapState.view})")
    page.mouse.move(cxm, cym)
    page.mouse.down()
    page.mouse.move(cxm + 150, cym + 100, steps=10)
    page.mouse.up()
    page.wait_for_timeout(400)
    v1 = page.evaluate("({...mapState.view})")
    check("drag pans view", abs(v1["x0"] - v0["x0"]) > 5 and abs(v1["y0"] - v0["y0"]) > 5,
          f"x0 {v0['x0']:.0f}->{v1['x0']:.0f} y0 {v0['y0']:.0f}->{v1['y0']:.0f}")
    # fit resets the view
    page.click("#map-fit")
    page.wait_for_timeout(400)
    v2 = page.evaluate("({...mapState.view})")
    check("fit resets view", abs(v2["x0"] - v0["x0"]) < 5, f"x0 {v2['x0']:.0f}")

    page.screenshot(path=f"C:/Users/diya0/AppData/Local/Temp/ui_test_{label}.png", full_page=False)
    check("no page errors", not errors, "; ".join(errors[:3]))


def main():
    with sync_playwright() as p:
        browser = p.chromium.launch(channel="chrome", headless=True)
        page = browser.new_page(viewport={"width": 1600, "height": 1000})
        run(page, "http://127.0.0.1:18789/bot_ui.html", "mock", live=False)
        run(page, "http://127.0.0.1:8789/bot_ui.html", "live", live=True)
        browser.close()
    print(f"\n{len(PASS)} passed, {len(FAIL)} failed")
    if FAIL:
        print("FAILURES:", FAIL)
        sys.exit(1)


if __name__ == "__main__":
    main()
