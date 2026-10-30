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
    ("restaurant", lambda t: _in(t, "amenity", "restaurant", "cafe", "fast_food", "pub", "biergarten", "food_court", "ice_cream")),
    ("camping", lambda t: _in(t, "tourism", "camp_site", "caravan_site")),
    ("lodging", lambda t: _in(t, "tourism", "hotel", "guest_house", "hostel", "motel", "chalet")),
    ("pharmacy", lambda t: _in(t, "amenity", "pharmacy")),
    ("hospital", lambda t: _in(t, "amenity", "hospital", "clinic")),
    ("info", lambda t: _in(t, "tourism", "information") and _in(t, "information", "office", "visitor_centre")),
    ("sight", lambda t: _in(t, "tourism", "attraction") or _in(t, "historic", "castle", "ruins", "monument")),
    ("atm", lambda t: _in(t, "amenity", "atm") or (_in(t, "amenity", "bank") and t.get("atm") == "yes")),
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
]

def category(t):
    for i, (_, match) in enumerate(CATS):
        if match(t):
            return i
    return -1

def extras(t):
    out = []
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
        places.append((cell_key(lat, lon), int(round(lat * 1e6)), int(round(lon * 1e6)), S(name[:80]), S(extras(t)), c))
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

    path = os.path.join(out, "switzerland.places")
    with open(path, "wb") as f:
        f.write(data)
    with gzip.open(path + ".gz", "wb", compresslevel=9) as f:
        f.write(data)
    info = {"built": built.isoformat(), "places": len(places), "bytes": len(data), "gz_bytes": os.path.getsize(path + ".gz"),
            "source": "OpenStreetMap contributors (ODbL), extract by Geofabrik", "categories": [k for k, _ in CATS]}
    with open(path + ".json", "w") as f:
        json.dump(info, f, indent=1)
    log("wrote", path, len(data), "bytes,", info["gz_bytes"], "gzipped")

if __name__ == "__main__":
    main()
