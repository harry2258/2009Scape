"""Robust overlay check: compare 15x15 patch color averages between the
canvas and map.webp at four spread-out geographic points inside the bot bbox.
Uses the plotted bot hits to derive the canvas<->tile transform, so it tests
the actual drawn alignment end-to-end."""

from PIL import Image
import statistics
from playwright.sync_api import sync_playwright

WEBP = Image.open("map.webp").convert("RGB")
CX, BX, CY, BY = 1.96378, -3346.81, -2.04242, 8569.48


def webp_patch_mean(tx, ty):
    px, py = CX * tx + BX, CY * ty + BY
    vals = [WEBP.getpixel((max(0, min(WEBP.width - 1, int(px + dx))),
                           max(0, min(WEBP.height - 1, int(py + dy)))))
            for dx in range(-7, 8) for dy in range(-7, 8)]
    return tuple(sum(v[i] for v in vals) / len(vals) for i in range(3))


def main():
    with sync_playwright() as p:
        browser = p.chromium.launch(channel="chrome", headless=True)
        page = browser.new_page(viewport={"width": 1600, "height": 1000})
        page.goto("http://127.0.0.1:8789/bot_ui.html")
        page.wait_for_timeout(7000)
        info = page.evaluate(
            """() => {
              if (!state.mapHits.length) return null;
              // derive canvas<->tile linear map from two distant hits
              const h0 = state.mapHits[0];
              const hN = state.mapHits[state.mapHits.length - 1];
              const t0 = h0.bot.location, tN = hN.bot.location;
              const ax = (hN.sx - h0.sx) / (tN.x - t0.x);
              const ay = (hN.sy - h0.sy) / (tN.y - t0.y);
              const toCanvas = (tx, ty) => [h0.sx + (tx - t0.x) * ax, h0.sy + (ty - t0.y) * ay];
              // four probe tiles: inset corners of the plotted bbox (outlier-robust)
              let x0=1e9,x1=-1e9,y0=1e9,y1=-1e9;
              const xs = state.bots.map(b=>b.location.x).sort((a,b)=>a-b);
              const ys = state.bots.map(b=>b.location.y).sort((a,b)=>a-b);
              const mx = xs[Math.floor(xs.length/2)], my = ys[Math.floor(ys.length/2)];
              for (const b of state.bots) {
                if (Math.abs(b.location.x-mx)>600 || Math.abs(b.location.y-my)>600) continue;
                x0=Math.min(x0,b.location.x); x1=Math.max(x1,b.location.x);
                y0=Math.min(y0,b.location.y); y1=Math.max(y1,b.location.y);
              }
              const w=x1-x0, h=y1-y0;
              const probes = [[x0+w*0.2, y0+h*0.2], [x0+w*0.8, y0+h*0.2],
                              [x0+w*0.2, y0+h*0.8], [x0+w*0.8, y0+h*0.8]];
              const c = document.getElementById('bot-map');
              const ctx = c.getContext('2d');
              return probes.map(([tx,ty]) => {
                const [sx,sy] = toCanvas(tx,ty);
                const d = ctx.getImageData(Math.round(sx)-7, Math.round(sy)-7, 15, 15).data;
                let r=0,g=0,b=0,n=d.length/4;
                for (let i=0;i<d.length;i+=4){r+=d[i];g+=d[i+1];b+=d[i+2];}
                return {tx: Math.round(tx), ty: Math.round(ty), canvas: [r/n,g/n,b/n]};
              });
            }"""
        )
        browser.close()
    if not info:
        print("NO HITS")
        return
    dists = []
    for probe in info:
        wm = webp_patch_mean(probe["tx"], probe["ty"])
        cm = probe["canvas"]
        dist = sum(abs(cm[i] - wm[i]) for i in range(3))
        dists.append(dist)
        print(f"tile({probe['tx']},{probe['ty']})  canvas ({cm[0]:5.0f},{cm[1]:5.0f},{cm[2]:5.0f})"
              f"  webp ({wm[0]:5.0f},{wm[1]:5.0f},{wm[2]:5.0f})  dist {dist:5.0f}")
    print("mean dist:", statistics.mean(dists), "(< ~60 = well aligned)")


if __name__ == "__main__":
    main()
