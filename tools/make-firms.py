#!/usr/bin/env python3
"""
Builds the offline pack of firms of the app: every entry of the Swiss commercial register
(Zefix, Federal Commercial Registry Office) with its registered address, placed on the map
with the official directory of building addresses (swisstopo).

    tools/make-firms.py out/ [--firms zefix.csv] [--addresses addresses.csv.zip] [--zips plz.csv]

Writes out/switzerland.firms (the binary the app reads), out/switzerland.firms.gz (what the app
downloads) and out/switzerland.firms.json (date and sizes, for the update check). Without the
optional files, the three sources are downloaded (about 230 MB).

Sources, all open government data:
  * the register: the linked-data service of the Confederation (lindas.admin.ch), one query;
    opendata.swiss « Zefix - Central Business Name Index », terms « Open use. Must provide the source. »
  * the addresses: data.geo.admin.ch, ch.swisstopo.amtliches-gebaeudeadressverzeichnis
  * the postcodes: data.geo.admin.ch, ch.swisstopo-vd.ortschaftenverzeichnis_plz (a firm whose
    street is not in the directory is put at the middle of its postcode, and flagged as such)

A firm is one row (name, street, postcode, town, position). Names are found through an index of
their words, sorted, so a word or the start of one is a binary search; the layout is read by
Firms.kt, the folding of words by Firms.fold (the two must agree: FirmsTest checks the pack).
"""
import argparse, csv, gzip, io, json, os, re, struct, sys, time, unicodedata, urllib.parse, urllib.request, zipfile
from datetime import date

VERSION = 1
MAGIC = 0x46484253  # "SBHF" little-endian
SPARQL = "https://lindas.admin.ch/query"
QUERY = """PREFIX schema: <http://schema.org/>
SELECT ?name ?street ?zip ?loc WHERE {
  ?c a <https://schema.ld.admin.ch/ZefixOrganisation> ; schema:legalName ?name ; schema:address ?a .
  ?a schema:postalCode ?zip ; schema:addressLocality ?loc .
  OPTIONAL { ?a schema:streetAddress ?street }
}"""
ADDRESSES = "https://data.geo.admin.ch/ch.swisstopo.amtliches-gebaeudeadressverzeichnis/amtliches-gebaeudeadressverzeichnis_ch/amtliches-gebaeudeadressverzeichnis_ch_2056.csv.zip"
ZIPS = "https://data.geo.admin.ch/ch.swisstopo-vd.ortschaftenverzeichnis_plz/ortschaftenverzeichnis_plz/ortschaftenverzeichnis_plz_4326.csv.zip"
UA = {"User-Agent": "swiss-bikers-pedestrians-map firms pack"}

# words that say nothing about which firm it is: the legal forms and the small words around them
STOP = set("""sa sarl ag gmbh sagl ltd llc inc co cie kg snc sas se sc scrl ab bv nv plc corp
en in liquidation liq liquidazione radiation
de du des la le les l d et and und der die das den dem von vom zum zur am im
di del della dei delle il lo e a au aux the of for fur pour per con""".split())
EXACT, STREET, ZIP = 0, 1, 2   # how a firm was placed: its building, its street, the middle of its postcode

def log(*a):
    print(time.strftime("%H:%M:%S"), *a, file=sys.stderr, flush=True)

def fold(s):
    """Lower case, no accents; everything that is not a letter or a digit separates words. Firms.fold does the same."""
    s = unicodedata.normalize("NFD", s)
    s = "".join(c for c in s if not unicodedata.combining(c)).lower()
    return re.sub(r"[^a-z0-9]+", " ", s).strip()

def tokens(name):
    return [t for t in fold(name).split() if len(t) >= 2 and t not in STOP]

def lv95_to_wgs84(e, n):
    """swisstopo's approximate formulas (about a metre)."""
    y = (e - 2600000) / 1e6; x = (n - 1200000) / 1e6
    lon = 2.6779094 + 4.728982 * y + 0.791484 * y * x + 0.1306 * y * x * x - 0.0436 * y ** 3
    lat = 16.9023892 + 3.238272 * x - 0.270978 * y * y - 0.002528 * x * x - 0.0447 * y * y * x - 0.0140 * x ** 3
    return lat * 100 / 36, lon * 100 / 36

NUMBER = re.compile(r"^(.*?)[ ,]+(\d+[ ]?[a-zA-Z]?(?:[./-]\d+[a-zA-Z]?)?)$")

def street_of(raw):
    """The street line of a registered address: the last line that is not « c/o … » (« c/o X\\nRue Y 22 »)."""
    lines = [l.strip() for l in (raw or "").replace("\r", "\n").split("\n")]
    lines = [l for l in lines if l and l.lower() != "null" and not l.lower().startswith(("c/o", "p.a.", "p. a.", "bei ", "chez "))]
    return lines[-1] if lines else ""

def split_number(street):
    m = NUMBER.match(street)
    return (m.group(1).strip(), m.group(2).replace(" ", "").lower()) if m else (street, "")

def fetch(url, data=None, headers=None):
    req = urllib.request.Request(url, data=data, headers={**UA, **(headers or {})})
    with urllib.request.urlopen(req, timeout=600) as r:
        return r.read()

def read_firms(path):
    if path:
        text = open(path, encoding="utf-8").read()
    else:
        log("asking the register (one query, about half a minute)")
        text = fetch(SPARQL, urllib.parse.urlencode({"query": QUERY}).encode(), {"Accept": "text/csv"}).decode("utf-8")
    csv.field_size_limit(10 ** 8)
    seen = set(); out = []
    for r in csv.DictReader(io.StringIO(text)):
        name = " ".join((r.get("name") or "").split()); z = (r.get("zip") or "").strip()
        if not name or not re.fullmatch(r"\d{4}", z):
            continue
        street = street_of(r.get("street")); loc = " ".join((r.get("loc") or "").split())
        k = (name, street, z)
        if k in seen:
            continue
        seen.add(k); out.append((name, street, int(z), loc))
    return out

def read_addresses(path):
    """(postcode, folded street, number) → position, and (postcode, folded street) → one position of that street."""
    if not path:
        log("downloading the directory of building addresses (140 MB)")
        blob = fetch(ADDRESSES)
        zf = zipfile.ZipFile(io.BytesIO(blob))
    else:
        zf = zipfile.ZipFile(path)
    exact = {}; streets = {}
    with zf.open(zf.namelist()[0]) as f:
        rd = csv.reader(io.TextIOWrapper(f, encoding="utf-8-sig"), delimiter=";")
        head = next(rd); col = {n: i for i, n in enumerate(head)}
        cs, cn, cz, ce, cnn, cst = col["STN_LABEL"], col["ADR_NUMBER"], col["ZIP_LABEL"], col["ADR_EASTING"], col["ADR_NORTHING"], col["ADR_STATUS"]
        for row in rd:
            if len(row) <= cnn or not row[ce] or row[cst] not in ("real", ""):
                continue
            try:
                z = int(row[cz][:4]); e = float(row[ce]); n = float(row[cnn])
            except ValueError:
                continue
            s = fold(row[cs])
            if not s:
                continue
            p = (e, n)
            exact[(z, s, row[cn].replace(" ", "").lower())] = p
            streets.setdefault((z, s), p)
    return exact, streets

def read_zips(path):
    if path:
        text = open(path, encoding="utf-8-sig").read()
    else:
        zf = zipfile.ZipFile(io.BytesIO(fetch(ZIPS)))
        text = zf.read(zf.namelist()[0]).decode("utf-8-sig")
    acc = {}
    for r in csv.DictReader(io.StringIO(text), delimiter=";"):
        try:
            z = int(r["PLZ4"]); lon = float(r["E"]); lat = float(r["N"])
        except (ValueError, KeyError):
            continue
        a = acc.setdefault(z, [0.0, 0.0, 0]); a[0] += lat; a[1] += lon; a[2] += 1
    return {z: (a[0] / a[2], a[1] / a[2]) for z, a in acc.items()}

def main():
    ap = argparse.ArgumentParser(description=__doc__.strip().split("\n")[0])
    ap.add_argument("out")
    ap.add_argument("--firms"); ap.add_argument("--addresses"); ap.add_argument("--zips")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    firms = read_firms(a.firms)
    log(len(firms), "firms")
    if len(firms) < 500000:
        sys.exit("the register answered with too few firms: no pack written")
    exact, streets = read_addresses(a.addresses)
    log(len(exact), "building addresses,", len(streets), "streets")
    zips = read_zips(a.zips)

    strings = bytearray(); offs = {}
    def s(text):
        """Offset of a string in the blob (u16 length, then UTF-8); each distinct string once."""
        o = offs.get(text)
        if o is None:
            raw = text.encode("utf-8")[:65535]
            o = len(strings); offs[text] = o
            strings.extend(struct.pack("<H", len(raw))); strings.extend(raw)
        return o
    s("")
    locs = {}; loc_list = []
    rows = bytearray(); index = {}; how = [0, 0, 0]; kept = 0
    for name, street, z, loc in firms:
        st, no = split_number(street); fs = fold(st)
        pos = exact.get((z, fs, no)); flag = EXACT
        if pos is None:
            pos = streets.get((z, fs)); flag = STREET
        if pos is not None:
            lat, lon = lv95_to_wgs84(*pos)
        elif z in zips:
            (lat, lon), flag = zips[z], ZIP
        else:
            continue   # a postcode that does not exist (Liechtenstein, a typing error): the firm cannot be placed
        how[flag] += 1
        li = locs.get(loc)
        if li is None:
            li = len(loc_list); locs[loc] = li; loc_list.append(loc)
        if li > 65535:
            continue
        rows.extend(struct.pack("<IIiiHHHH", s(name), s(street), round(lat * 1e6), round(lon * 1e6), z, li, flag, 0))
        for t in set(tokens(name)):
            index.setdefault(t, []).append(kept)
        kept += 1
    log(kept, "placed:", how[EXACT], "at their building,", how[STREET], "in their street,", how[ZIP], "at their postcode")

    toks = sorted(index)
    tok_table = bytearray(); postings = bytearray()
    for t in toks:
        ids = index[t]
        tok_table.extend(struct.pack("<III", s(t), len(postings) // 4, len(ids)))
        postings.extend(struct.pack("<%dI" % len(ids), *ids))
    loc_table = b"".join(struct.pack("<I", s(l)) for l in loc_list)

    built = date.today()
    HEAD = 48
    o_strings = HEAD; o_firms = o_strings + len(strings); o_locs = o_firms + len(rows)
    o_tokens = o_locs + len(loc_table); o_post = o_tokens + len(tok_table)
    header = struct.pack("<IIIIIIIIIIII", MAGIC, VERSION, built.year * 10000 + built.month * 100 + built.day,
                         kept, len(toks), len(loc_list), o_strings, o_firms, o_locs, o_tokens, o_post, 0)
    data = header + bytes(strings) + bytes(rows) + loc_table + bytes(tok_table) + bytes(postings)
    path = os.path.join(a.out, "switzerland.firms")
    with open(path, "wb") as f:
        f.write(data)
    with open(path, "rb") as f, gzip.open(path + ".gz", "wb", compresslevel=9) as g:
        g.write(f.read())
    info = {"built": built.isoformat(), "firms": kept, "exact": how[EXACT], "street": how[STREET], "zip": how[ZIP],
            "bytes": len(data), "gz_bytes": os.path.getsize(path + ".gz"),
            "source": "Zefix, Federal Commercial Registry Office; addresses: swisstopo"}
    with open(path + ".json", "w") as f:
        json.dump(info, f, indent=1)
    log("wrote", path, len(data), "bytes,", info["gz_bytes"], "gzipped")

if __name__ == "__main__":
    main()
