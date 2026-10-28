# Swiss Bike-Hike Map

A private, single-screen map app for cycling and hiking in Switzerland. Old-school GPS
interface: pick **BIKE** or **WALK**, see the official trails in the signpost colours,
long-press to put stops on the map (each one numbered, the last is the destination), **GO** to
route on-device through them, **NAV** for turn-by-turn guidance with one big arrow, an elevation
profile and the time of arrival, **REC** to record a GPX. A GPX track can be followed as it is
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
| Offline | MapLibre offline packs for the area on screen, zoom 8 to 15, refreshed automatically once a week over Wi-Fi |

Data © swisstopo, © SchweizMobil, © OpenStreetMap contributors.

## Build

    ./gradlew assembleDebug        # or assembleRelease, then sign

`local.properties` must point at an Android SDK. The `brouter` module is plain Java copied from
upstream (`brouter/UPSTREAM-COMMIT.txt`); the two profile files and `lookups.dat` are in `app/src/main/assets/brouter`.

## Data on the phone

- Routing: `Android/data/com.freedomfighter.swissbikehike/files/brouter/segments4/E5_N45.rd5` and `E10_N45.rd5`
  (about 450 MB), downloaded by the app over Wi-Fi on first **GO**, refreshed from the menu (brouter.de rebuilds them weekly).
- Favourites: `favourites.json` in the app's private storage; export as GPX waypoints to `Documents/Swiss Bike-Hike Map/`.
- Navigated trips: `trips/history.json` and one GPX per trip in the app's private storage.
- Saved areas: MapLibre's own database in the app's private storage.
- Recorded tracks: `Documents/Swiss Bike-Hike Map/<date>_<mode>.gpx`.

## Weight of a full offline Switzerland (sampled 2026-10-28, bounding box of the country)

| Layer | Zoom | Size |
|---|---|---|
| Routing, both modes | | 450 MB |
| Vector base map | 6 to 14 | about 1.0 GB |
| Hillshade (relief tiles used by the style) | 6 to 14 | about 1.4 GB |

(The 1:25 000 paper map, dropped in 0.3.0, would have added 3.6 GB at zoom 15 or 11 GB at zoom 16.)

The box includes a good share of France, Italy, Germany and Austria; Switzerland alone is roughly half.
