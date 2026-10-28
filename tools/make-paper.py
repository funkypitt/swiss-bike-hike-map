#!/usr/bin/env python3
"""Build paper.pmtiles: the swisstopo national map as an offline PMTiles archive for Swiss Bike-Hike Map.

Tiles come from the free swisstopo WMTS (© swisstopo, open government data). The archive goes to
Android/data/com.freedomfighter.swissbikehike/files/paper/paper.pmtiles on the phone; the app uses it
instead of the online map when present (restart the app after copying).

Examples
  # Lausanne and the Lavaux, zoom 8..16 (about 60 MB)
  python3 make-paper.py --bbox 6.45,46.40,7.00,46.70 --out paper.pmtiles
  # whole country at full detail (several GB, hours)
  python3 make-paper.py --switzerland --maxzoom 16 --out paper.pmtiles

Needs: pip install pmtiles
"""
import argparse, math, os, sys, time, urllib.request
from concurrent.futures import ThreadPoolExecutor
from pmtiles.writer import Writer
from pmtiles.tile import zxy_to_tileid, TileType, Compression

LAYER = "ch.swisstopo.pixelkarte-farbe"
URL = "https://wmts.geo.admin.ch/1.0.0/%s/default/current/3857/%d/%d/%d.jpeg"
SWITZERLAND = (5.95, 45.80, 10.50, 47.82)

def tile_range(bbox, z):
    w, s, e, n = bbox
    k = 2 ** z
    x0 = int((w + 180) / 360 * k); x1 = int((e + 180) / 360 * k)
    def ty(lat):
        r = math.radians(lat)
        return int((1 - math.log(math.tan(r) + 1 / math.cos(r)) / math.pi) / 2 * k)
    return x0, min(x1, k - 1), ty(n), min(ty(s), k - 1)

def fetch(z, x, y, tries=4):
    url = URL % (LAYER, z, x, y)
    for i in range(tries):
        try:
            with urllib.request.urlopen(url, timeout=30) as r:
                return r.read()
        except Exception as ex:
            if i == tries - 1:
                print("failed", url, ex, file=sys.stderr)
                return None
            time.sleep(1.5 * (i + 1))

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bbox", help="west,south,east,north in degrees")
    ap.add_argument("--switzerland", action="store_true")
    ap.add_argument("--minzoom", type=int, default=8)
    ap.add_argument("--maxzoom", type=int, default=16, help="16 = native 1:25 000 detail, 17 = sharper on dense screens")
    ap.add_argument("--out", default="paper.pmtiles")
    ap.add_argument("--threads", type=int, default=8)
    a = ap.parse_args()
    bbox = SWITZERLAND if a.switzerland else tuple(float(v) for v in a.bbox.split(","))
    jobs = []
    for z in range(a.minzoom, a.maxzoom + 1):
        x0, x1, y0, y1 = tile_range(bbox, z)
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                jobs.append((z, x, y))
    jobs.sort(key=lambda t: zxy_to_tileid(*t))      # clustered archive, cheap to read
    print(f"{len(jobs)} tiles, zoom {a.minzoom}-{a.maxzoom}")
    total = 0
    with open(a.out, "wb") as f:
        w = Writer(f)
        with ThreadPoolExecutor(a.threads) as pool:
            for n, (job, data) in enumerate(zip(jobs, pool.map(lambda j: fetch(*j), jobs)), 1):
                if data:
                    w.write_tile(zxy_to_tileid(*job), data)
                    total += len(data)
                if n % 500 == 0:
                    print(f"{n}/{len(jobs)}  {total/1e6:.0f} MB", flush=True)
        header = {
            "tile_type": TileType.JPEG,
            "tile_compression": Compression.NONE,
            "min_lon_e7": int(bbox[0] * 1e7), "min_lat_e7": int(bbox[1] * 1e7),
            "max_lon_e7": int(bbox[2] * 1e7), "max_lat_e7": int(bbox[3] * 1e7),
            "center_zoom": a.minzoom,
            "center_lon_e7": int((bbox[0] + bbox[2]) / 2 * 1e7), "center_lat_e7": int((bbox[1] + bbox[3]) / 2 * 1e7),
        }
        w.finalize(header, {"name": "swisstopo national map", "attribution": "© swisstopo", "format": "jpeg"})
    print(f"wrote {a.out}: {os.path.getsize(a.out)/1e6:.0f} MB")

if __name__ == "__main__":
    main()
