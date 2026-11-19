#!/usr/bin/env python3
"""
The signposted tours of Switzerland, as one small file for the app's assets.

Source: « Wanderland Schweiz », « Mountainbikeland Schweiz » and « Veloland Schweiz » (Federal Roads Office FEDRO,
SwitzerlandMobility and the cantons), open data on data.geo.admin.ch — free use, the source must
be named. The GeoPackages hold the routes (name, kind) and their stages (from, to, length, climb,
fitness, technique, walking times) as heaps of unordered little lines in Swiss coordinates with
elevations; this tool sews each stage into one line from its start to its end, turns it into
WGS84, thins it (3 m) and writes:

    "SBHT" u8 version | u32 index length | index (JSON, UTF-8) | lines

Each index entry has the offset and the point count of its line. A line is a run of zigzag
varints: latitude and longitude in 1e-6 degrees, elevation in decimetres, each as the difference
from the point before.

The index also says when the pack was built and how recent its source is ("source": the newest
« updated » of the three GeoPackages); the app takes a pack from the server only when its source
is newer than the one it has.

Standard library only.  Usage:
    tools/make-tours.py [--out app/src/main/assets/tours.pack] [--keep DIR]    the pack shipped with the app
    tools/make-tours.py --release DIR     switzerland.tours.gz + .json for the rolling release « tours-data »
    tools/make-tours.py --source          only prints how recent the source is
The data change about once a year; the workflow tours-data looks every week and rebuilds then.
"""
import argparse, collections, datetime, gzip, json, math, os, sqlite3, struct, sys, tempfile, urllib.parse, urllib.request

SOURCES = [
    ("walk", "https://data.geo.admin.ch/ch.astra.wanderland/wanderland/wanderland_2056.gpkg"),
    ("mtb", "https://data.geo.admin.ch/ch.astra.mountainbikeland/mountainbikeland/mountainbikeland_2056.gpkg"),
    ("bike", "https://data.geo.admin.ch/ch.astra.veloland/veloland/veloland_2056.gpkg"),
]
# fewer stages than this in a land = the source is broken or has changed shape: nothing is published
LEAST = {"walk": 800, "mtb": 300, "bike": 250}
TOLERANCE_M = 3.0
LEVEL = {"easy": 1, "medium": 2, "difficult": 3}
KIND = {"national": "n", "regional": "r", "local": "l", "lokal": "l", "handicap": "h"}


def fetch(url, path):
    if os.path.exists(path) and os.path.getsize(path) > 1_000_000:
        return
    print("fetching", url, file=sys.stderr)
    req = urllib.request.Request(url, headers={"User-Agent": "swiss-bikers-pedestrians-map tours pack"})
    with urllib.request.urlopen(req, timeout=120) as r, open(path + ".part", "wb") as f:
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
    os.replace(path + ".part", path)


def parts_of(blob):
    """The line strings of a GeoPackage geometry (LineString or MultiLineString, with or without Z/M)."""
    if blob is None or blob[:2] != b"GP":
        return []
    env = (blob[3] >> 1) & 7
    off = 8 + {0: 0, 1: 32, 2: 48, 3: 48, 4: 64}[env]

    def geom(o):
        bo = "<" if blob[o] == 1 else ">"
        t = struct.unpack_from(bo + "I", blob, o + 1)[0]
        o += 5
        base, flavour = t % 1000, t // 1000
        dim = 2 + (1 if flavour in (1, 3) else 0) + (1 if flavour in (2, 3) else 0)
        has_z = flavour in (1, 3)
        n = struct.unpack_from(bo + "I", blob, o)[0]
        o += 4
        if base == 2:
            flat = struct.unpack_from(bo + "d" * (dim * n), blob, o)
            pts = [(flat[i * dim], flat[i * dim + 1], flat[i * dim + 2] if has_z else float("nan")) for i in range(n)]
            return [pts], o + 8 * dim * n
        if base == 5:
            out = []
            for _ in range(n):
                p, o = geom(o)
                out += p
            return out, o
        raise ValueError("geometry type %d" % t)

    return geom(off)[0]


def sew(parts):
    """
    One line through the unordered parts: they are the edges of a graph whose nodes are their ends.
    Walks from an end of the graph (or anywhere on a loop), always taking a part not yet used and
    preferring the one that leaves the most behind it, so that a short spur does not end the walk.
    Returns the line and the share of the total length it covers.
    """
    parts = [p for p in parts if len(p) >= 2]
    if not parts:
        return [], 0.0
    key = lambda p: (round(p[0] * 2), round(p[1] * 2))          # ends meet within half a metre
    length = lambda p: sum(math.hypot(a[0] - b[0], a[1] - b[1]) for a, b in zip(p, p[1:]))
    lens = [length(p) for p in parts]
    at = collections.defaultdict(list)
    for i, p in enumerate(parts):
        at[key(p[0])].append(i)
        at[key(p[-1])].append(i)
    total = sum(lens)

    def reach(start_node, used):
        """Length of unused parts reachable from a node."""
        seen, stack, got = set(), [start_node], 0.0
        visited = set()
        while stack:
            n = stack.pop()
            if n in visited:
                continue
            visited.add(n)
            for i in at[n]:
                if i in used or i in seen:
                    continue
                seen.add(i)
                got += lens[i]
                a, b = key(parts[i][0]), key(parts[i][-1])
                stack.append(b if a == n else a)
        return got

    def walk(node):
        used, line, done = set(), [], 0.0
        while True:
            options = [i for i in at[node] if i not in used]
            if not options:
                break
            if len(options) > 1:
                # the way that keeps the most of the rest reachable; a loop closes last
                def after(i):
                    a, b = key(parts[i][0]), key(parts[i][-1])
                    other = b if a == node else a
                    return reach(other, used | {i}) + lens[i]
                options.sort(key=after, reverse=True)
            i = options[0]
            used.add(i)
            p = parts[i] if key(parts[i][0]) == node else parts[i][::-1]
            line += p if not line else p[1:]
            done += lens[i]
            node = key(p[-1])
        return line, done

    ends = [n for n, v in at.items() if len(v) == 1]
    starts = ends if ends else [key(parts[0][0])]
    best = max((walk(s) for s in starts[:6]), key=lambda r: r[1])
    return best[0], best[1] / total if total else 0.0


class Places:
    """Where a named place is (Swiss grid), asked once from GeoAdmin's search and kept in a file beside this tool."""

    def __init__(self, path):
        self.path, self.dirty = path, False
        self.known = json.load(open(path, encoding="utf-8")) if os.path.exists(path) else {}

    def find(self, name):
        name = name.split("(")[0].strip() if not name.startswith("(") else name.strip("()").strip()
        if not name:
            return None
        if name not in self.known:
            self.known[name] = None
            self.dirty = True
            try:
                q = urllib.parse.urlencode({"searchText": name, "type": "locations", "origins": "gg25,gazetteer,zipcode", "sr": "2056", "limit": "1"})
                req = urllib.request.Request("https://api3.geo.admin.ch/rest/services/api/SearchServer?" + q, headers={"User-Agent": "swiss-bikers-pedestrians-map tours pack"})
                with urllib.request.urlopen(req, timeout=20) as r:
                    res = json.load(r).get("results", [])
                if res:
                    a = res[0]["attrs"]
                    e, n = max(a["x"], a["y"]), min(a["x"], a["y"])
                    self.known[name] = [round(e), round(n)]
            except Exception as ex:
                print("no place for", name, ex, file=sys.stderr)
        return self.known[name]

    def save(self):
        if self.dirty:
            with open(self.path, "w", encoding="utf-8") as f:
                json.dump(self.known, f, ensure_ascii=False, indent=0, sort_keys=True)


def orient(line, s, nxt, prev, places):
    """
    Must the sewn line be turned round to run from the stage's start to its end? Told by, in
    order: the stage after or before it (they meet), the climb of the record against the line's
    own, the places its ends are named after. Returns (flip, how).
    """
    dist = lambda a, b: math.hypot(a[0] - b[0], a[1] - b[1])
    if dist(line[0], line[-1]) < 150:
        return False, "loop"
    if nxt:
        d_end = min(dist(line[-1], nxt[0]), dist(line[-1], nxt[-1]))
        d_start = min(dist(line[0], nxt[0]), dist(line[0], nxt[-1]))
        if min(d_end, d_start) < 300 and abs(d_end - d_start) > 300:
            return d_start < d_end, "chain"
    if prev:
        d_end = min(dist(line[-1], prev[0]), dist(line[-1], prev[-1]))
        d_start = min(dist(line[0], prev[0]), dist(line[0], prev[-1]))
        if min(d_end, d_start) < 300 and abs(d_end - d_start) > 300:
            return d_end < d_start, "chain"
    up, down = s["HoeheAufE"] or 0, s["HoeheAbE"] or 0
    fwd, back = climb(line), climb(line[::-1])
    if abs(up - down) >= 40 and abs(fwd - back) >= 40:
        return (up > down) != (fwd > back), "climb"
    a, z = places.find(s["NameS"] or ""), places.find(s["NameZ"] or "")
    if a and z:
        as_is = dist(line[0], a) + dist(line[-1], z)
        turned = dist(line[-1], a) + dist(line[0], z)
        if abs(as_is - turned) > 500:
            return turned < as_is, "names"
    return False, "unknown"


def wgs84(e, n):
    """Swiss LV95 to WGS84, swisstopo's approximate formulas (about a metre)."""
    y = (e - 2_600_000) / 1e6
    x = (n - 1_200_000) / 1e6
    lon = 2.6779094 + 4.728982 * y + 0.791484 * y * x + 0.1306 * y * x * x - 0.0436 * y ** 3
    lat = 16.9023892 + 3.238272 * x - 0.270978 * y * y - 0.002528 * x * x - 0.0447 * y * y * x - 0.0140 * x ** 3
    return lat * 100 / 36, lon * 100 / 36


def thin(pts, tol):
    """Douglas-Peucker in the plane of the Swiss grid (metres)."""
    if len(pts) < 3:
        return pts
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        s, e = stack.pop()
        if e - s < 2:
            continue
        ax, ay = pts[s][0], pts[s][1]
        bx, by = pts[e][0], pts[e][1]
        vx, vy = bx - ax, by - ay
        l2 = vx * vx + vy * vy
        worst, wd = -1, tol
        for i in range(s + 1, e):
            wx, wy = pts[i][0] - ax, pts[i][1] - ay
            t = 0.0 if l2 < 1e-9 else max(0.0, min(1.0, (wx * vx + wy * vy) / l2))
            d = math.hypot(wx - t * vx, wy - t * vy)
            if d > wd:
                wd, worst = d, i
        if worst >= 0:
            keep[worst] = True
            stack.append((s, worst))
            stack.append((worst, e))
    return [p for p, k in zip(pts, keep) if k]


def climb(pts):
    """Ascent with a 10 m hysteresis, as the app counts it."""
    up, lo, hi = 0.0, None, None
    for p in pts:
        z = p[2]
        if z != z:
            continue
        if lo is None:
            lo = hi = z
            continue
        hi = max(hi, z)
        lo = min(lo, z)
        if z - lo >= 10:
            up += z - lo
            lo = hi = z
        if hi - z >= 10:
            hi = lo = z
    return up


def varint(n):
    n = (n << 1) ^ (n >> 63)
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return out


def encode(pts):
    out = bytearray()
    pla = plo = pz = 0
    for e, n, z in pts:
        lat, lon = wgs84(e, n)
        la, lo = round(lat * 1e6), round(lon * 1e6)
        zz = pz if z != z else round(z * 10)
        out += varint(la - pla) + varint(lo - plo) + varint(zz - pz)
        pla, plo, pz = la, lo, zz
    return bytes(out)


def source_date():
    """The newest « updated » of the GeoPackages, as the server's catalogue gives it (an ISO date and time)."""
    newest = ""
    for land, url in SOURCES:
        collection = url.split("/")[3]
        req = urllib.request.Request("https://data.geo.admin.ch/api/stac/v0.9/collections/%s/items" % collection,
                                     headers={"User-Agent": "swiss-bikers-pedestrians-map tours pack"})
        with urllib.request.urlopen(req, timeout=60) as r:
            assets = json.load(r)["features"][0]["assets"]
        newest = max(newest, next(v["updated"] for k, v in assets.items() if k.endswith(".gpkg")))
    return newest


def minutes(v):
    try:
        return round(float(v) * 60)
    except (TypeError, ValueError):
        return 0


def read(land, path, report, places):
    db = sqlite3.connect(path)
    db.row_factory = sqlite3.Row
    routes = {}
    for r in db.execute("select * from Route"):
        # one entry per route number: the outward line when there are two
        if r["NrR"] not in routes or r["Abwicklung"] == "Hinweg":
            routes[r["NrR"]] = r
    stages = collections.defaultdict(list)
    for s in db.execute("select * from Etappe order by NrR, NrEtappe"):
        stages[s["NrR"]].append(s)
    out = []
    for nr, r in routes.items():
        mine = [s for s in stages.get(nr, []) if s["Abwicklung"] != "Rückweg"]
        # a stage listed for both ways keeps its outward line
        by_nr = {}
        for s in mine:
            if s["NrEtappe"] not in by_nr or s["Abwicklung"] == "Hinweg":
                by_nr[s["NrEtappe"]] = s
        chosen = [by_nr[k] for k in sorted(by_nr)]
        sewn = [sew(parts_of(s["geom"])) for s in chosen]
        for k, s in enumerate(chosen):
            line, share = sewn[k]
            if len(line) < 2:
                report["empty"] += 1
                continue
            flip, how = orient(line, s, sewn[k + 1][0] if k + 1 < len(sewn) else None, sewn[k - 1][0] if k > 0 else None, places)
            report[how] += 1
            if flip:
                line = line[::-1]
            if share < 0.9:
                report["partial"].append("%s %s/%s %.0f %%" % (land, nr, s["NrEtappe"], share * 100))
            km = sum(math.hypot(a[0] - b[0], a[1] - b[1]) for a, b in zip(line, line[1:])) / 1000
            thinned = thin(line, TOLERANCE_M)
            lat, lon = wgs84(thinned[0][0], thinned[0][1])
            lat2, lon2 = wgs84(thinned[-1][0], thinned[-1][1])
            out.append(({
                "l": land, "k": KIND.get((r["Typ_TR"] or "").lower(), "l"), "nr": nr, "name": (r["NameR"] or "").strip(),
                "st": s["NrEtappe"], "of": len(chosen),
                "from": (s["NameS"] or "").strip(), "to": (s["NameZ"] or "").strip(),
                "m": round(km * 1000), "up": s["HoeheAufE"] or 0, "down": s["HoeheAbE"] or 0,
                "fit": LEVEL.get(s["KonditionE"], 0), "tech": LEVEL.get(s["TechnikE"], 0),
                "min": minutes(s["ZeitStZiE"]), "back": minutes(s["ZeitZiStE"]),
                "loop": 1 if math.hypot(line[0][0] - line[-1][0], line[0][1] - line[-1][1]) < 150 else 0,
                "lat": round(lat, 5), "lon": round(lon, 5), "lat2": round(lat2, 5), "lon2": round(lon2, 5), "n": len(thinned),
            }, encode(thinned)))
            report["km_stated"] += s["DistanzE"] or 0
            report["km_sewn"] += km
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "tours.pack"))
    ap.add_argument("--keep", help="folder for the downloaded GeoPackages (kept between runs)")
    ap.add_argument("--release", metavar="DIR", help="write switzerland.tours.gz and switzerland.tours.json there instead of --out")
    ap.add_argument("--source", action="store_true", help="print how recent the source is, and stop")
    a = ap.parse_args()
    source = source_date()
    if a.source:
        print(source)
        return
    folder = a.keep or tempfile.mkdtemp(prefix="tours-")
    os.makedirs(folder, exist_ok=True)
    report = collections.Counter()
    report["partial"] = []
    places = Places(os.path.join(os.path.dirname(__file__), "tours-places.json"))
    items = []
    for land, url in SOURCES:
        path = os.path.join(folder, land + ".gpkg")
        fetch(url, path)
        got = read(land, path, report, places)
        print("%s: %d stages of %d routes" % (land, len(got), len({i[0]['nr'] for i in got})), file=sys.stderr)
        if len(got) < LEAST[land]:
            sys.exit("%s: only %d stages, at least %d expected: nothing written" % (land, len(got), LEAST[land]))
        items += got
    blob = bytearray()
    index = []
    for entry, line in items:
        entry["o"] = len(blob)
        entry["b"] = len(line)
        blob += line
        index.append(entry)
    built = datetime.date.today().isoformat()
    head = json.dumps({"built": built, "source": source, "tours": index}, ensure_ascii=False, separators=(",", ":")).encode()
    pack = b"SBHT" + bytes([1]) + struct.pack("<I", len(head)) + head + blob
    if a.release:
        os.makedirs(a.release, exist_ok=True)
        a.out = os.path.join(a.release, "switzerland.tours.gz")
        with open(a.out, "wb") as f:
            f.write(gzip.compress(pack, 9, mtime=0))
        with open(os.path.join(a.release, "switzerland.tours.json"), "w") as f:
            json.dump({"built": built, "source": source, "tours": len(index), "bytes": len(pack), "gz_bytes": os.path.getsize(a.out)}, f)
    else:
        with open(a.out, "wb") as f:
            f.write(pack)
    print("%d tours, %.1f MB (index %.0f kB, lines %.1f MB); %d km stated, %d km sewn; empty %d; partial %d" % (
        len(index), os.path.getsize(a.out) / 1e6, len(head) / 1e3, len(blob) / 1e6, report["km_stated"], report["km_sewn"], report["empty"], len(report["partial"])), file=sys.stderr)
    places.save()
    print("direction from: the next or previous stage %d, the climb %d, the names of the ends %d, a loop %d, unknown %d" % (
        report["chain"], report["climb"], report["names"], report["loop"], report["unknown"]), file=sys.stderr)
    for p in report["partial"][:40]:
        print("  partial:", p, file=sys.stderr)


if __name__ == "__main__":
    main()
