# Swiss Bikers and Pedestrians Map — notes

Technical notes kept from the private versions; the README has the overview.

A private, single-screen map app for cycling and hiking in Switzerland. Old-school GPS
interface: pick **BIKE** or **WALK**, see the official trails in the signpost colours,
long-press to put stops on the map (each one numbered, the last is the destination), **GO** to
route on-device through them, **NAV** for turn-by-turn guidance with one big arrow, an elevation
profile and the time of arrival (a foreground service: it goes on with the screen off or another
app in front, with the next turn in the notification), **REC** to record a GPX. A GPX track can be followed as it is
(turns read off its shape). Stops can be saved as favourites (places, people and firms from the
directory, bare coordinates), shared as text, received from other apps (geo: links, shared text,
GPX files), and lists of places imported from GPX, KML/KMZ, GeoJSON, CSV or plain text with
coordinates (WGS84, LV95, LV03 or degrees-minutes). Night map (manual or after sunset), local
history of navigated trips, whole-country offline pack in one button, six languages
(en, fr, de, es, pt, ru).

Everything is recycled:

| Part | Source |
|---|---|
| Map rendering | [MapLibre Native](https://maplibre.org) (BSD) |
| Base map | swisstopo vector tiles and style, clutter layers removed (`MapStyle.kt`) |
| Official hiking trails | the `sac_scale` attribute of swisstopo's vector tiles: yellow / white-red-white / white-blue-white |
| Official cycling routes | swisstopo WMTS overlays Veloland (blue) and Mountainbikeland (orange) |
| Closures and detours | swisstopo WMS overlays, online only |
| Routing | [BRouter](https://github.com/abrensch/brouter) (MIT) embedded as the `brouter` module, OpenStreetMap data from brouter.de (profiles `trekking` and `hiking-mountain`) |
| Turn instructions | BRouter's own voice hints (`turnInstructionMode`), read through `brouter/.../TurnHints.java` |
| Search | GeoAdmin search API; people and firms through the search.ch directory with the person's own key |
| Offline | MapLibre offline packs for the area on screen, zoom 8 to 15, refreshed from the menu only (0.6.1: nothing automatic at launch, it stalled the map) |

Data © swisstopo, © SchweizMobil, © OpenStreetMap contributors.

## Build

    ./gradlew assembleDebug        # or assembleRelease, then sign

`local.properties` must point at an Android SDK. The `brouter` module is plain Java copied from
upstream (`brouter/UPSTREAM-COMMIT.txt`); the two profile files and `lookups.dat` are in `app/src/main/assets/brouter`.

## Data on the phone

- Routing: `Android/data/com.freedomfighter.swissbikehike/files/brouter/segments4/E5_N45.rd5` and `E10_N45.rd5`
  (about 450 MB), downloaded by the app over Wi-Fi on first **GO**, refreshed from the menu (brouter.de rebuilds them weekly).
- Favourites: `favourites.json` in the app's private storage; export as GPX waypoints to `Documents/Swiss Bikers and Pedestrians Map/`.
- Navigated trips: `trips/history.json` and one GPX per trip in the app's private storage.
- Saved areas: MapLibre's own database in the app's private storage.
- Recorded tracks: `Documents/Swiss Bikers and Pedestrians Map/<date>_<mode>.gpx`.

## Weight of a full offline Switzerland (sampled 2026-10-28, bounding box of the country)

| Layer | Zoom | Size |
|---|---|---|
| Routing, both modes | | 450 MB |
| Vector base map | 6 to 14 | about 1.0 GB |
| Hillshade (relief tiles used by the style) | 6 to 14 | about 1.4 GB |

(The 1:25 000 paper map, dropped in 0.3.0, would have added 3.6 GB at zoom 15 or 11 GB at zoom 16.)

The box includes a good share of France, Italy, Germany and Austria; Switzerland alone is roughly half.

## Public transport (0.6.0)

* **Lines and stops** come from the swisstopo base map itself, so they are offline wherever the map
  is: `transportation` layer, class `rail` (subclass rail / narrow_gauge / rack_rail / funicular),
  `transit` (tram / subway), `cable_car` / `gondola` / `chair_lift` / `aerialway`, `ferry` /
  `car_ferry`; sidings (`service=siding`) are skipped. Stops are the `poi` layer, classes `bus`,
  `railway`, `ferry_terminal`, `funicular`, `aerialway`, minus `stop_position`.
* **Online**: transport.opendata.ch (`locations`, `connections`, `stationboard`), no key, real-time
  delays and platforms. The API goes stop to stop, so `Planner` tries the nearest stop and the
  nearest station proper (no comma in its name, Swiss convention) at both ends, adds the walks,
  and drops dominated journeys.
* **Offline pack**: `tools/make-transit.py` turns the official GTFS (4.6 GB unpacked, 42 M stop
  times, 2.6 M trips) into a 65 MB binary (15 MB gzipped): stations only (35 550), trips sharing a
  stop sequence and relative times become one profile (193 k), trips differing only by service
  days are merged (1.7 M kept), 366-bit service bitmaps, footpaths between stations within 500 m,
  change times from transfers.txt. Memory-mapped on the phone, read on demand (`Transit.kt`).
  Planner = RAPTOR, six rounds, origin and destination stops within 1 km, yesterday's services
  looked at for trips past midnight; Lausanne → Montreux in 55 ms, Lausanne → Wengen in 650 ms on
  the desktop JVM. Walks are routed by BRouter (hiking) when the routing data is there.
* **Guidance** (`NavService`, `stepTransit`): one instruction at a time, from the position and the
  clock (a ticker every 10 s): WALK to the stop, BOARD (vibration two minutes before), ON (stops
  left, counted by position, by the clock without GPS), GET OFF (within 400 m or one stop before,
  long vibration), CHANGE, ARRIVED; a ride missed by three minutes triggers a new plan from here.
* The pack is downloaded by the system DownloadManager (Wi-Fi) from the release `transit-data`,
  unpacked and checked; the menu compares the local `switzerland.transit.json` with the server's and
  says when a newer pack exists, the download stays a button (0.6.1). Emulator trick: let the app create `files/transit/` before pushing the pack with adb,
  a directory created by the shell user is not readable by the app.

## Routing profiles tuned for Switzerland (0.6.2)

Measured with `TagCli` (scratch tool printing BRouter's way tags along the route) on brouter.de's
E5_N45 segment, share of the route on OSM route relations (`route_hiking_*`, `route_foot_*`,
`route_bicycle_[inrl]cn`), upstream profile → tuned profile:

| route | upstream | tuned |
|---|---|---|
| hike Chexbres → Lutry | 38 % (8.9 km) | 79 % (10.2 km) |
| hike Lausanne station → Sauvabelin | 75 % | 92 % |
| hike Montreux → Les Avants | 50 % | 50 % (already on the signposted path) |
| bike Lausanne → Vevey | 16 % (lake road, flat) | 16 % (unchanged, the route climbs 200 m through Lavaux) |
| bike Lausanne → Echallens | 82 % | 86 % |

Hiking: `hiking_routes_preference` 0.20 → 0.6 (ways outside a hiking relation cost ×1.6),
`SAC_scale_limit` 3 → 4 with `SAC_K2` 0.8 (alpine trails allowed but dear). Bike: non-route ways
+0.25 instead of +0.05 (0.4 changed nothing on the samples), main roads without a cycle lane dearer
(primary 4, secondary 2.2, tertiary 1.6). The Veloland routes themselves keep cost 1.
