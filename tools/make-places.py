#!/usr/bin/env python3
"""
Builds the offline pack of places (fountains, shelters, huts, bike shops, bakeries…) of the app
from OpenStreetMap, out of the Geofabrik extract of Switzerland.

    tools/make-places.py [extract.osm.pbf | file.osm] out/   (downloads the extract when no input is given)

Writes out/switzerland.places (the binary the app reads), out/switzerland.places.gz (what the
app downloads) and out/switzerland.places.json (date and sizes, for the update check).

Every place is one point (the centroid for buildings and areas) with a category, a name and a
few extra tags (opening hours, phone, website, town, address, elevation…). The places are sorted
by grid cell (0.02°) so the app finds the ones around a point without reading the file; names
are searched linearly. The layout is read by Places.kt; the categories are known to the app by
their key, in CATS below. Needs pyosmium (pip install osmium).
"""
import gzip, json, os, struct, sys, time, urllib.request
from datetime import date

import osmium
from osmium.filter import KeyFilter

EXTRACT = "https://download.geofabrik.de/europe/switzerland-latest.osm.pbf"
VERSION = 1
CELL = 0.02
LAT0, LON0 = 45.0, 5.0
BOUNDS = (45.5, 5.5, 48.2, 11.0)   # south, west, north, east: the extract spills a little over the border
KEYS = ("amenity", "shop", "tourism", "leisure", "natural", "man_made", "historic")

def log(*a):
    print(time.strftime("%H:%M:%S"), *a, file=sys.stderr, flush=True)

def _in(t, key, *values):
    return t.get(key) in values

# key, matcher(tags) -> bool. The order is the order of the categories in the pack; the app maps the keys to its labels.
CATS = [
    ("water", lambda t: _in(t, "amenity", "drinking_water") or _in(t, "man_made", "water_tap")
        or (t.get("drinking_water") == "yes" and (_in(t, "amenity", "fountain") or _in(t, "natural", "spring") or _in(t, "man_made", "water_well")))),
    ("fountain", lambda t: _in(t, "amenity", "fountain") and t.get("drinking_water") != "yes"),
    ("toilets", lambda t: _in(t, "amenity", "toilets")),
    ("shelter", lambda t: _in(t, "amenity", "shelter") and t.get("shelter_type") != "public_transport"),
    ("hut", lambda t: _in(t, "tourism", "alpine_hut", "wilderness_hut")),
    ("picnic", lambda t: _in(t, "tourism", "picnic_site") or _in(t, "leisure", "picnic_table")),
    ("fireplace", lambda t: _in(t, "leisure", "firepit") or _in(t, "amenity", "bbq")),
    ("viewpoint", lambda t: _in(t, "tourism", "viewpoint")),
    ("bathing", lambda t: _in(t, "natural", "beach") or _in(t, "leisure", "swimming_area", "bathing_place", "water_park")
        or _in(t, "amenity", "public_bath") or (_in(t, "leisure", "swimming_pool") and t.get("access") != "private" and "name" in t)),
    ("bike_shop", lambda t: _in(t, "shop", "bicycle")),
    ("bike_repair", lambda t: _in(t, "amenity", "bicycle_repair_station")),
    ("bike_rental", lambda t: _in(t, "amenity", "bicycle_rental")),
    ("bike_parking", lambda t: _in(t, "amenity", "bicycle_parking")),
    ("charging", lambda t: _in(t, "amenity", "charging_station") and t.get("bicycle") == "yes"),
    ("outdoor", lambda t: _in(t, "shop", "outdoor", "sports")),
    ("bakery", lambda t: _in(t, "shop", "bakery")),
    ("grocery", lambda t: _in(t, "shop", "supermarket", "convenience", "greengrocer", "farm", "general", "cheese", "dairy", "deli", "butcher", "health_food")),
    ("restaurant", lambda t: _in(t, "amenity", "restaurant")),
    ("cafe", lambda t: _in(t, "amenity", "cafe", "ice_cream") or _in(t, "shop", "pastry", "confectionery", "chocolate", "coffee", "tea")),
    ("bar", lambda t: _in(t, "amenity", "bar", "pub", "biergarten", "nightclub")),
    ("fast_food", lambda t: _in(t, "amenity", "fast_food", "food_court")),
    ("camping", lambda t: _in(t, "tourism", "camp_site", "caravan_site")),
    ("lodging", lambda t: _in(t, "tourism", "hotel", "guest_house", "hostel", "motel", "chalet")),
    ("pharmacy", lambda t: _in(t, "amenity", "pharmacy")),
    ("hospital", lambda t: _in(t, "amenity", "hospital", "clinic")),
    ("info", lambda t: _in(t, "tourism", "information") and _in(t, "information", "office", "visitor_centre")),
    ("sight", lambda t: _in(t, "tourism", "attraction") or _in(t, "historic", "castle", "ruins", "monument")),
    ("atm", lambda t: _in(t, "amenity", "atm") or (_in(t, "amenity", "bank") and t.get("atm") == "yes")),
    ("post", lambda t: _in(t, "amenity", "post_office")),
    ("bank", lambda t: _in(t, "amenity", "bank")),
    ("hairdresser", lambda t: _in(t, "shop", "hairdresser", "beauty", "barber")),
    # every other shop that has a name (vacant, disused and nameless ones say nothing to a passer-by)
    ("shop", lambda t: "shop" in t and t["shop"] not in ("vacant", "no", "yes") and "name" in t),
]

# extra tags kept with a place, as "short=value" lines; the app shows them in the place's detail
EXTRAS = [
    ("oh", ("opening_hours",)),
    ("tel", ("phone", "contact:phone", "contact:mobile")),
    ("web", ("website", "contact:website")),
    ("city", ("addr:city",)),
    ("ele", ("ele",)),
    ("cuisine", ("cuisine",)),
    ("fee", ("fee",)),
    ("op", ("operator",)),
    ("type", ("shelter_type", "information", "network")),
    ("cap", ("capacity",)),
    ("mail", ("email", "contact:email")),
    ("pc", ("addr:postcode",)),
]

def category(t):
    for i, (_, match) in enumerate(CATS):
        if match(t):
            return i
    return -1

def extras(t, cat):
    out = []
    if CATS[cat][0] == "shop":
        out.append("shop=" + t["shop"][:40])   # what kind of shop (clothes, books, florist…): the app names it
    for short, keys in EXTRAS:
        for k in keys:
            v = t.get(k)
            if v:
                out.append(f"{short}={v.replace(chr(10), ' ').strip()[:120]}")
                break
    street = t.get("addr:street")
    if street:
        out.append("addr=" + (street + " " + t.get("addr:housenumber", "")).strip()[:80])
    return "\n".join(out)

class Strings:
    def __init__(self):
        self.idx = {"": 0}; self.list = [""]
    def __call__(self, s):
        s = s or ""
        i = self.idx.get(s)
        if i is None:
            i = len(self.list); self.idx[s] = i; self.list.append(s)
        return i
    def pack(self):
        blob = b""; offs = [0]
        parts = []
        for s in self.list:
            b = s.encode("utf-8"); parts.append(b); offs.append(offs[-1] + len(b))
        blob = b"".join(parts)
        return struct.pack("<I", len(self.list)) + struct.pack("<%dI" % len(offs), *offs) + blob

def cell_key(lat, lon):
    return int((lat - LAT0) / CELL) * 1024 + int((lon - LON0) / CELL)

# ---- streets: the names of the ways, so a route's steps can say « Avenue de la Gare » ----
#
# Appended after the places, behind a trailer (offset, "SBHS"): apps that predate it read the
# places as before and never look there. Every named way (and every unnamed one that carries a
# ref, like the main roads) is one record: name, kind, its points as 1e-6° steps; a grid of
# 0.01° cells lists the ways whose segments touch each cell.
STREET_MAGIC = 0x53484253   # "SBHS" little-endian
SCELL = 0.01
# highway values kept, in the order of their kind number (0 = other); the app names unnamed ones by kind
HIGHWAYS = ["motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "living_street",
            "service", "pedestrian", "track", "cycleway", "footway", "path", "steps", "bridleway", "road"]

def scell_key(lat, lon):
    return int((lat - LAT0) / SCELL) * 2048 + int((lon - LON0) / SCELL)

def simplify(pts, tol):
    """Douglas-Peucker on 1e-6° points, tolerance in metres: the indices kept."""
    if len(pts) < 3:
        return list(range(len(pts)))
    import math
    kx = 0.11132 * math.cos(math.radians(pts[0][0] / 1e6)); ky = 0.11054
    keep = [False] * len(pts); keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        a, b = stack.pop()
        if b - a < 2:
            continue
        ax, ay = pts[a][1] * kx, pts[a][0] * ky; bx, by = pts[b][1] * kx, pts[b][0] * ky
        vx, vy = bx - ax, by - ay; l2 = vx * vx + vy * vy
        worst, wd = -1, tol
        for i in range(a + 1, b):
            px, py = pts[i][1] * kx - ax, pts[i][0] * ky - ay
            t = 0.0 if l2 < 1e-9 else max(0.0, min(1.0, (px * vx + py * vy) / l2))
            dx, dy = px - t * vx, py - t * vy
            d = math.sqrt(dx * dx + dy * dy)
            if d > wd:
                worst, wd = i, d
        if worst >= 0:
            keep[worst] = True; stack.append((a, worst)); stack.append((worst, b))
    return [i for i in range(len(pts)) if keep[i]]

def streets(src, S):
    """The streets section (bytes) of the pack: named ways with their geometry, by grid cell."""
    south, west, north, east = BOUNDS
    raw = []         # (name, kind, node ids, [(lat1e6, lon1e6)…])
    ways = []        # (name idx, kind, [(lat1e6, lon1e6)…])
    log("reading the ways of", src)
    fp = osmium.FileProcessor(src, osmium.osm.NODE | osmium.osm.WAY).with_locations().with_filter(KeyFilter("highway"))
    for o in fp:
        if not o.is_way():
            continue
        t = o.tags
        hw = t.get("highway")
        if hw not in HIGHWAYS:
            continue
        name = t.get("name") or t.get("name:fr") or t.get("name:de") or t.get("name:it") or ""
        if not name:
            ref = t.get("ref")
            if not ref or hw not in ("motorway", "trunk", "primary", "secondary"):
                continue
            name = ref
        ids = []; pts = []
        for nd in o.nodes:
            loc = nd.location
            if not loc.valid():
                continue
            ids.append(nd.ref); pts.append((int(round(loc.lat * 1e6)), int(round(loc.lon * 1e6))))
        if len(pts) < 2:
            continue
        la, lo = pts[0][0] / 1e6, pts[0][1] / 1e6
        if not (south <= la <= north and west <= lo <= east):
            continue
        raw.append((name[:80], HIGHWAYS.index(hw) + 1, ids, pts))
    log("ways with a name:", len(raw), "points:", sum(len(w[3]) for w in raw))
    # the pieces of one street (OpenStreetMap splits them at every bridge, speed limit…) joined end to end
    by_name = {}
    for w in raw:
        by_name.setdefault(w[0], []).append(w)
    for name, group in by_name.items():
        ends = {}
        for i, w in enumerate(group):
            ends.setdefault(w[2][0], []).append(i); ends.setdefault(w[2][-1], []).append(i)
        used = [False] * len(group)
        for i, w in enumerate(group):
            if used[i]:
                continue
            used[i] = True
            kind, ids, pts = w[1], list(w[2]), list(w[3])
            grown = True
            while grown:
                grown = False
                for at_end in (True, False):
                    node = ids[-1] if at_end else ids[0]
                    for j in ends.get(node, []):
                        if used[j]:
                            continue
                        o_ids, o_pts = group[j][2], group[j][3]
                        if o_ids[0] != node:
                            o_ids = o_ids[::-1]; o_pts = o_pts[::-1]
                        used[j] = True; grown = True
                        if at_end:
                            ids += o_ids[1:]; pts += o_pts[1:]
                        else:
                            ids = o_ids[::-1][:-1] + ids; pts = o_pts[::-1][:-1] + pts
                        break
            pts = [pts[k] for k in simplify(pts, 3.0)]
            # steps of a way are 16-bit: a longer leg gets points in between
            fine = [pts[0]]
            for p in pts[1:]:
                q = fine[-1]
                n = max(1, int(max(abs(p[0] - q[0]), abs(p[1] - q[1])) // 30000) + 1)
                for k in range(1, n + 1):
                    fine.append((q[0] + (p[0] - q[0]) * k // n, q[1] + (p[1] - q[1]) * k // n))
            ways.append((S(name), kind, fine))
    log("streets kept:", len(ways), "points:", sum(len(w[2]) for w in ways))

    # cell -> ways whose segments' boxes touch it
    cells = {}
    for wi, (_, _, pts) in enumerate(ways):
        seen = set()
        for (a, b) in zip(pts, pts[1:]):
            la0, la1 = sorted((a[0], b[0])); lo0, lo1 = sorted((a[1], b[1]))
            for cy in range(int((la0 / 1e6 - LAT0) / SCELL), int((la1 / 1e6 - LAT0) / SCELL) + 1):
                for cx in range(int((lo0 / 1e6 - LON0) / SCELL), int((lo1 / 1e6 - LON0) / SCELL) + 1):
                    k = cy * 2048 + cx
                    if k not in seen:
                        seen.add(k); cells.setdefault(k, []).append(wi)
    keys = sorted(cells)
    refs = []; index = []
    for k in keys:
        index.append((k, len(refs))); refs.extend(cells[k])
    index.append((0x7FFFFFFF, len(refs)))

    table = []; deltas = []; first = 0
    for (name, kind, pts) in ways:
        table.append(struct.pack("<IIiiHH", name, first, pts[0][0], pts[0][1], len(pts), kind))
        for (a, b) in zip(pts, pts[1:]):
            deltas.append(struct.pack("<hh", b[0] - a[0], b[1] - a[1]))
        first += len(pts) - 1
    # layout: counts, then ways (20 bytes), steps (4 bytes), cells (8 bytes), refs (4 bytes)
    body = (struct.pack("<IIII", len(ways), first, len(index), len(refs)) + b"".join(table) + b"".join(deltas)
            + b"".join(struct.pack("<iI", k, f) for k, f in index) + struct.pack("<%dI" % len(refs), *refs))
    return body, len(ways)

def fetch(path):
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    log("downloading", EXTRACT)
    req = urllib.request.Request(EXTRACT, headers={"User-Agent": "swiss-bikers-pedestrians-map (+https://github.com/funkypitt/swiss-bike-hike-map)"})
    with urllib.request.urlopen(req, timeout=120) as r, open(path, "wb") as f:
        while True:
            chunk = r.read(1 << 20)
            if not chunk: break
            f.write(chunk)
    return path

def main():
    args = sys.argv[1:]
    src = args[0] if len(args) >= 2 else None
    out = args[-1] if args else "out"
    os.makedirs(out, exist_ok=True)
    if src is None:
        src = fetch(os.path.join("extract", "switzerland-latest.osm.pbf"))
    S = Strings()
    for k, _ in CATS:
        S(k)
    places = []     # (cell, lat1e6, lon1e6, name idx, extra idx, cat)
    counts = [0] * len(CATS)
    south, west, north, east = BOUNDS
    log("reading", src)
    # areas (closed ways, multipolygons) are assembled from the ways, which must flow through: ask for everything, keep nodes and areas
    fp = osmium.FileProcessor(src).with_areas().with_filter(KeyFilter(*KEYS))
    seen = 0
    for o in fp:
        if o.is_way() or o.is_relation():
            continue
        seen += 1
        t = {tag.k: tag.v for tag in o.tags}
        c = category(t)
        if c < 0:
            continue
        if o.is_node():
            if not o.location.valid():
                continue
            lat, lon = o.location.lat, o.location.lon
        else:
            # an area (closed way or multipolygon): the centroid of its first outer ring
            n = 0; sl = 0.0; so = 0.0
            try:
                for ring in o.outer_rings():
                    for nd in ring:
                        if nd.location.valid():
                            n += 1; sl += nd.location.lat; so += nd.location.lon
                    break
            except Exception:
                continue
            if n == 0:
                continue
            lat, lon = sl / n, so / n
        if not (south <= lat <= north and west <= lon <= east):
            continue
        name = t.get("name") or t.get("name:fr") or t.get("name:de") or t.get("name:it") or ""
        places.append((cell_key(lat, lon), int(round(lat * 1e6)), int(round(lon * 1e6)), S(name[:80]), S(extras(t, c)), c))
        counts[c] += 1
    log("objects with the keys:", seen, "places kept:", len(places))
    for (k, _), n in zip(CATS, counts):
        log(f"  {k:12s} {n}")

    places.sort(key=lambda p: (p[0], S.list[p[3]].lower(), p[1], p[2]))
    cells = []
    for i, p in enumerate(places):
        if not cells or cells[-1][0] != p[0]:
            cells.append((p[0], i))
    cells.append((0x7FFFFFFF, len(places)))

    street_body, n_streets = streets(src, S)
    strings = S.pack()
    cats = struct.pack("<H", len(CATS)) + b"".join(struct.pack("<I", S(k)) for k, _ in CATS)
    body_places = b"".join(struct.pack("<iiIIHH", p[1], p[2], p[3], p[4], p[5], 0) for p in places)
    body_cells = struct.pack("<I", len(cells)) + b"".join(struct.pack("<iI", k, first) for k, first in cells)
    head = 32
    sec_strings = head
    sec_cats = sec_strings + len(strings)
    sec_places = sec_cats + len(cats)
    sec_cells = sec_places + len(body_places)
    built = date.today()
    header = struct.pack("<IIIIIIII", 0x50484253, VERSION, built.year * 10000 + built.month * 100 + built.day, len(places),
                         sec_strings, sec_cats, sec_places, sec_cells)
    data = header + strings + cats + body_places + body_cells
    sec_streets = len(data)
    data = data + street_body + struct.pack("<II", sec_streets, STREET_MAGIC)

    path = os.path.join(out, "switzerland.places")
    with open(path, "wb") as f:
        f.write(data)
    with gzip.open(path + ".gz", "wb", compresslevel=9) as f:
        f.write(data)
    info = {"built": built.isoformat(), "places": len(places), "streets": n_streets, "bytes": len(data), "gz_bytes": os.path.getsize(path + ".gz"),
            "source": "OpenStreetMap contributors (ODbL), extract by Geofabrik", "categories": [k for k, _ in CATS]}
    with open(path + ".json", "w") as f:
        json.dump(info, f, indent=1)
    log("wrote", path, len(data), "bytes,", info["gz_bytes"], "gzipped")

if __name__ == "__main__":
    main()
