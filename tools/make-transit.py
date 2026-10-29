#!/usr/bin/env python3
"""
Builds the offline public-transport timetable of the app from the official Swiss GTFS feed
(opentransportdata.swiss, free, no key).

    tools/make-transit.py [gtfs.zip | directory] out/   (downloads the feed when no input is given)

Writes out/switzerland.transit (the binary the app reads), out/switzerland.transit.gz (what
the app downloads) and out/switzerland.transit.json (feed date and size, for the update check).

The binary keeps the whole year of service in about a tenth of the GTFS size: trips that share
a stop sequence and the same relative times are one "profile"; trips that only differ by their
service days are merged into one trip with the union of the days; the service days are 366-bit
bitmaps. Stops are stations (GTFS location_type 1), platforms are kept as a code per trip stop.
Everything is little-endian; the layout is read by Transit.kt. Pure standard library.
"""
import csv, gzip, io, json, math, os, struct, sys, time, urllib.request, zipfile
from collections import defaultdict
from datetime import date, timedelta

PERMALINK = "https://data.opentransportdata.swiss/en/dataset/timetable-2026-gtfs2020/permalink"
VERSION = 1
NDAYS = 366
FOOT_MAX_M = 500.0        # footpaths between stations closer than this
FOOT_MAX_N = 12           # at most this many neighbours per station
FOOT_MPS = 1.1            # walking pace used for the footpaths
CHANGE_S = 120            # default time to change within a station

def log(*a):
    print(time.strftime("%H:%M:%S"), *a, file=sys.stderr, flush=True)

def hms(t):
    h, m, s = t.split(":")
    return int(h) * 60 + int(m) + (1 if int(s) >= 30 else 0)

class Strings:
    def __init__(self):
        self.idx = {"": 0}; self.list = [""]
    def __call__(self, s):
        s = s or ""
        i = self.idx.get(s)
        if i is None:
            i = len(self.list); self.idx[s] = i; self.list.append(s)
        return i

def open_feed(path):
    """Returns a function name -> text file object, from a zip or a directory."""
    if path is None:
        os.makedirs("feed", exist_ok=True)
        path = os.path.join("feed", "gtfs.zip")
        log("downloading", PERMALINK)
        # the portal answers 403 to urllib's default User-Agent; it takes one that names the client
        req = urllib.request.Request(PERMALINK, headers={"User-Agent": "swiss-bikers-pedestrians-map (+https://github.com/funkypitt/swiss-bike-hike-map)"})
        with urllib.request.urlopen(req, timeout=120) as r, open(path, "wb") as f:
            while True:
                chunk = r.read(1 << 20)
                if not chunk: break
                f.write(chunk)
    if os.path.isdir(path):
        return lambda n: open(os.path.join(path, n), encoding="utf-8-sig", newline=""), path
    z = zipfile.ZipFile(path)
    return lambda n: io.TextIOWrapper(z.open(n), encoding="utf-8-sig", newline=""), path

def main():
    args = [a for a in sys.argv[1:]]
    src = args[0] if len(args) >= 2 else None
    out = args[-1] if args else "out"
    os.makedirs(out, exist_ok=True)
    rd, src = open_feed(src)
    S = Strings()

    # --- feed dates ---
    with rd("feed_info.txt") as f:
        feed = next(csv.DictReader(f))
    start = date(int(feed["feed_start_date"][:4]), int(feed["feed_start_date"][4:6]), int(feed["feed_start_date"][6:]))
    feed_version = feed.get("feed_version", "")
    log("feed", feed_version, "from", start)

    # --- stops: stations only; every platform maps to its station ---
    stations = {}        # stop_id (station) -> index
    station_rows = []    # (lat, lon, didok, name)
    child_parent = {}
    rows = []
    with rd("stops.txt") as f:
        for r in csv.DictReader(f):
            rows.append(r)
    for r in rows:
        if r.get("location_type") == "1" or (not r.get("parent_station")):
            stations[r["stop_id"]] = len(station_rows)
            d = r.get("didok") or "0"
            station_rows.append((float(r["stop_lat"]), float(r["stop_lon"]), int(d) if d.isdigit() else 0, S(r["stop_name"]), r.get("platform_code", "")))
    stop_station = {}
    platform_of = {}
    for r in rows:
        sid = r["stop_id"]
        if sid in stations:
            stop_station[sid] = stations[sid]
        else:
            p = r.get("parent_station")
            if p in stations:
                stop_station[sid] = stations[p]
            else:
                stations[sid] = len(station_rows)
                station_rows.append((float(r["stop_lat"]), float(r["stop_lon"]), 0, S(r["stop_name"]), ""))
                stop_station[sid] = stations[sid]
        pc = r.get("platform_code", "")
        platform_of[sid] = pc if len(pc) <= 5 else ""   # a few platforms carry an internal id instead of a code
        S(platform_of[sid])   # interned now, so the platform codes keep 16-bit indices
    del rows
    log("stations", len(station_rows))

    # --- services: 366-day bitmaps ---
    svc_bits = {}
    def bits_for(sid):
        b = svc_bits.get(sid)
        if b is None:
            b = bytearray(NDAYS // 8 + 1); svc_bits[sid] = b
        return b
    with rd("calendar.txt") as f:
        for r in csv.DictReader(f):
            days = [r["monday"], r["tuesday"], r["wednesday"], r["thursday"], r["friday"], r["saturday"], r["sunday"]]
            if "1" not in days: continue
            s = date(int(r["start_date"][:4]), int(r["start_date"][4:6]), int(r["start_date"][6:]))
            e = date(int(r["end_date"][:4]), int(r["end_date"][4:6]), int(r["end_date"][6:]))
            b = bits_for(r["service_id"])
            d = max(s, start)
            while d <= e:
                i = (d - start).days
                if i >= NDAYS: break
                if days[d.weekday()] == "1": b[i >> 3] |= 1 << (i & 7)
                d += timedelta(days=1)
    with rd("calendar_dates.txt") as f:
        for r in csv.DictReader(f):
            d = date(int(r["date"][:4]), int(r["date"][4:6]), int(r["date"][6:]))
            i = (d - start).days
            if i < 0 or i >= NDAYS: continue
            b = bits_for(r["service_id"])
            if r["exception_type"] == "1": b[i >> 3] |= 1 << (i & 7)
            else: b[i >> 3] &= ~(1 << (i & 7)) & 0xFF
    log("services", len(svc_bits))

    # --- routes and agencies ---
    agencies = {}
    with rd("agency.txt") as f:
        for r in csv.DictReader(f): agencies[r["agency_id"]] = r["agency_name"]
    routes = {}          # route_id -> index
    route_rows = []      # (type, shortIdx, agencyIdx)
    with rd("routes.txt") as f:
        for r in csv.DictReader(f):
            routes[r["route_id"]] = len(route_rows)
            short = r.get("route_short_name") or r.get("route_desc") or ""
            route_rows.append((int(r["route_type"] or 0), S(short), S(agencies.get(r["agency_id"], "")), S(r.get("route_desc", ""))))
    log("routes", len(route_rows))

    # --- trips ---
    trip_meta = {}       # trip_id -> (route idx, service_id, headsignIdx, numberIdx)
    with rd("trips.txt") as f:
        for r in csv.DictReader(f):
            ri = routes.get(r["route_id"])
            if ri is None: continue
            trip_meta[r["trip_id"]] = (ri, r["service_id"], S(r.get("trip_headsign", "")), S(r.get("trip_short_name", "")))
    log("trips", len(trip_meta))
    freqs = defaultdict(list)
    try:
        with rd("frequencies.txt") as f:
            for r in csv.DictReader(f):
                freqs[r["trip_id"]].append((hms(r["start_time"]), hms(r["end_time"]), max(1, int(r["headway_secs"]) // 60)))
    except KeyError:
        pass

    # --- stop_times: streamed, grouped by trip; profiles and merged trips ---
    patterns = {}        # (route idx, stops tuple) -> index
    pattern_rows = []    # (route idx, stops tuple)
    profiles = {}        # (pattern idx, arr tuple, dep tuple, plat tuple) -> index
    profile_rows = []
    merged = {}          # (profile idx, start, headsign, number) -> set of service ids
    seen_trips = set()
    n_rows = 0

    def flush(tid, seq):
        meta = trip_meta.get(tid)
        if meta is None or len(seq) < 2: return
        seq.sort(key=lambda x: x[0])
        stops_t = tuple(stop_station[s] for _, _, _, s in seq if s in stop_station)
        if len(stops_t) < 2: return
        pk = (meta[0], stops_t)
        pi = patterns.get(pk)
        if pi is None:
            pi = len(pattern_rows); patterns[pk] = pi; pattern_rows.append(pk)
        t0 = seq[0][2]
        arr = tuple(max(0, a - t0) for _, a, _, s in seq if s in stop_station)
        dep = tuple(max(0, d - t0) for _, _, d, s in seq if s in stop_station)
        plat = tuple(S(platform_of.get(s, "")) for _, _, _, s in seq if s in stop_station)
        if not any(plat): plat = ()
        fk = (pi, arr, dep, plat)
        fi = profiles.get(fk)
        if fi is None:
            fi = len(profile_rows); profiles[fk] = fi; profile_rows.append(fk)
        starts = [t0]
        if tid in freqs:
            starts = []
            for s, e, hw in freqs[tid]:
                t = s
                while t <= e:
                    starts.append(t); t += hw
        for st in starts:
            merged.setdefault((fi, st, meta[2], meta[3]), set()).add(meta[1])

    with rd("stop_times.txt") as f:
        rdr = csv.reader(f)
        head = next(rdr)
        ix = {n: i for i, n in enumerate(head)}
        c_trip, c_arr, c_dep, c_stop, c_seq = ix["trip_id"], ix["arrival_time"], ix["departure_time"], ix["stop_id"], ix["stop_sequence"]
        cur = None; seq = []
        for r in rdr:
            n_rows += 1
            if n_rows % 5_000_000 == 0: log("stop_times", n_rows, "profiles", len(profile_rows), "trips", len(merged))
            tid = r[c_trip]
            if tid != cur:
                if cur is not None:
                    flush(cur, seq)
                    seen_trips.add(cur)
                if tid in seen_trips:
                    sys.exit("stop_times.txt is not grouped by trip: " + tid)
                cur = tid; seq = []
            seq.append((int(r[c_seq]), hms(r[c_arr] or r[c_dep]), hms(r[c_dep] or r[c_arr]), r[c_stop]))
        if cur is not None: flush(cur, seq)
    log("stop_times", n_rows, "patterns", len(pattern_rows), "profiles", len(profile_rows), "merged trips", len(merged))
    del trip_meta, seen_trips

    # --- merged service bitmaps ---
    bitmaps = {}; bitmap_rows = []
    def bitmap_idx(sids):
        b = bytearray(NDAYS // 8 + 1)
        for s in sids:
            sb = svc_bits.get(s)
            if sb is None: continue
            for i in range(len(b)): b[i] |= sb[i]
        key = bytes(b)
        i = bitmaps.get(key)
        if i is None:
            i = len(bitmap_rows); bitmaps[key] = i; bitmap_rows.append(key)
        return i
    trips = []           # (pattern idx, profile idx, start, bitmap idx, headsign, number)
    for (fi, st, hs, num), sids in merged.items():
        bi = bitmap_idx(sids)
        if not any(bitmap_rows[bi]): continue
        trips.append((profile_rows[fi][0], fi, st, bi, hs, num))
    del merged
    trips.sort(key=lambda t: (t[0], t[2], t[1]))
    log("bitmaps", len(bitmap_rows), "trips kept", len(trips))

    # --- trips per pattern, patterns per stop ---
    pat_first = [0] * len(pattern_rows); pat_count = [0] * len(pattern_rows)
    for i, t in enumerate(trips):
        if pat_count[t[0]] == 0: pat_first[t[0]] = i
        pat_count[t[0]] += 1
    stop_pats = defaultdict(list)
    for pi, (ri, stops_t) in enumerate(pattern_rows):
        if pat_count[pi] == 0: continue
        for pos, s in enumerate(stops_t):
            stop_pats[s].append((pi, pos))

    # --- footpaths between nearby stations ---
    log("footpaths")
    cell = 0.006  # ~500 m in latitude
    grid = defaultdict(list)
    for i, (lat, lon, _, _, _) in enumerate(station_rows):
        grid[(int(lat / cell), int(lon / (cell * 1.5)))].append(i)
    def dist(a, b):
        la1, lo1 = station_rows[a][0], station_rows[a][1]; la2, lo2 = station_rows[b][0], station_rows[b][1]
        k = math.cos(math.radians(la1))
        return math.hypot((la2 - la1) * 111_320.0, (lo2 - lo1) * 111_320.0 * k)
    foot = [[] for _ in station_rows]
    served = set(stop_pats.keys())
    for i, (lat, lon, _, _, _) in enumerate(station_rows):
        if i not in served: continue
        cx, cy = int(lat / cell), int(lon / (cell * 1.5))
        cand = []
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for j in grid.get((cx + dx, cy + dy), ()):
                    if j == i or j not in served: continue
                    d = dist(i, j)
                    if d <= FOOT_MAX_M: cand.append((d, j))
        cand.sort()
        foot[i] = [(j, max(60, int(d * 1.3 / FOOT_MPS))) for d, j in cand[:FOOT_MAX_N]]
    # change time inside a station from transfers.txt (type 2, same station)
    change = [CHANGE_S] * len(station_rows)
    try:
        with rd("transfers.txt") as f:
            for r in csv.DictReader(f):
                if r.get("transfer_type") != "2" or not r.get("min_transfer_time"): continue
                a = stop_station.get(r["from_stop_id"]); b = stop_station.get(r["to_stop_id"])
                if a is not None and a == b and not r.get("from_route_id") and not r.get("from_trip_id"):
                    change[a] = min(1800, int(r["min_transfer_time"]))
    except KeyError:
        pass

    # --- write ---
    log("writing")
    buf = io.BytesIO()
    w = buf.write
    def u8(v): w(struct.pack("<B", v))
    def u16(v): w(struct.pack("<H", v))
    def u32(v): w(struct.pack("<I", v))
    def i32(v): w(struct.pack("<i", v))
    sections = {}
    w(b"SBHT"); u32(VERSION)
    u32(int(feed["feed_start_date"])); u16(NDAYS)
    hdr_pos = buf.tell()
    SECTIONS = ["strings", "stops", "routes", "patterns", "pattern_stops", "profiles", "profile_data", "bitmaps", "trips", "stop_patterns", "footpaths"]
    for _ in SECTIONS: u32(0)   # section offsets, filled in at the end
    # strings
    sections["strings"] = buf.tell()
    u32(len(S.list))
    off = 0; enc = [s.encode("utf-8") for s in S.list]
    for e in enc: u32(off); off += len(e)
    u32(off)
    for e in enc: w(e)
    # stops
    sections["stops"] = buf.tell()
    u32(len(station_rows))
    for lat, lon, didok, name, _ in station_rows:
        i32(int(round(lat * 1e6))); i32(int(round(lon * 1e6))); u32(didok); u32(name)
    # routes
    sections["routes"] = buf.tell()
    u32(len(route_rows))
    for t, sh, ag, desc in route_rows: u16(t); u32(sh); u32(ag); u32(desc)
    # patterns: fixed part + stop lists
    sections["patterns"] = buf.tell()
    u32(len(pattern_rows))
    off = 0
    for pi, (ri, stops_t) in enumerate(pattern_rows):
        u32(ri); u16(len(stops_t)); u32(off); u32(pat_first[pi]); u32(pat_count[pi]); off += len(stops_t)
    sections["pattern_stops"] = buf.tell()
    u32(off)
    for ri, stops_t in pattern_rows:
        w(struct.pack("<%dI" % len(stops_t), *stops_t))
    # profiles: offsets then data
    sections["profiles"] = buf.tell()
    u32(len(profile_rows))
    data = io.BytesIO(); offs = []
    for pi, arr, dep, plat in profile_rows:
        offs.append(data.tell())
        n = len(arr)
        data.write(struct.pack("<IHB", pi, n, 1 if plat else 0))
        data.write(struct.pack("<%dH" % n, *[min(v, 65535) for v in arr]))
        data.write(struct.pack("<%dH" % n, *[min(v, 65535) for v in dep]))
        if plat: data.write(struct.pack("<%dH" % n, *[min(v, 65535) for v in plat]))
    for o in offs: u32(o)
    sections["profile_data"] = buf.tell()
    u32(data.tell()); w(data.getvalue())
    # bitmaps
    sections["bitmaps"] = buf.tell()
    u32(len(bitmap_rows)); u16(NDAYS // 8 + 1)
    for b in bitmap_rows: w(b)
    # trips
    sections["trips"] = buf.tell()
    u32(len(trips))
    for pat, prof, st, bi, hs, num in trips:
        w(struct.pack("<IHIII", prof, min(st, 65535), bi, hs, num))
    # stop -> patterns
    sections["stop_patterns"] = buf.tell()
    data = io.BytesIO(); offs = []
    for i in range(len(station_rows)):
        offs.append(data.tell())
        lst = stop_pats.get(i, [])
        data.write(struct.pack("<H", len(lst)))
        for pi, pos in lst: data.write(struct.pack("<IH", pi, pos))
    for o in offs: u32(o)
    u32(data.tell()); w(data.getvalue())
    # footpaths and change times
    sections["footpaths"] = buf.tell()
    data = io.BytesIO(); offs = []
    for i in range(len(station_rows)):
        offs.append(data.tell())
        data.write(struct.pack("<HH", change[i], len(foot[i])))
        for j, sec in foot[i]: data.write(struct.pack("<IH", j, min(sec, 65535)))
    for o in offs: u32(o)
    u32(data.tell()); w(data.getvalue())
    raw = bytearray(buf.getvalue())
    for i, name in enumerate(SECTIONS):
        struct.pack_into("<I", raw, hdr_pos + 4 * i, sections[name])
    path = os.path.join(out, "switzerland.transit")
    with open(path, "wb") as f: f.write(raw)
    with gzip.open(path + ".gz", "wb", compresslevel=9) as f: f.write(raw)
    meta = {"feed": feed_version, "start": feed["feed_start_date"], "end": feed.get("feed_end_date", ""), "bytes": len(raw),
            "gz_bytes": os.path.getsize(path + ".gz"), "stops": len(station_rows), "trips": len(trips), "built": time.strftime("%Y-%m-%d")}
    with open(path + ".json", "w") as f: json.dump(meta, f, indent=1)
    log("done", json.dumps(meta))

if __name__ == "__main__":
    main()
