# Swiss Bike-Hike Map

A private, single-screen map app for cycling and hiking in Switzerland. Old-school GPS
interface: pick **BIKE** or **WALK**, see the official trails in the signpost colours,
long-press to drop a pin, **GO** to route on-device, **REC** to record a GPX.

Everything is recycled:

| Part | Source |
|---|---|
| Map rendering | [MapLibre Native](https://maplibre.org) (BSD) |
| Base map | swisstopo vector tiles and style, clutter layers removed (`MapStyle.kt`) |
| Official hiking trails | the `sac_scale` attribute of swisstopo's vector tiles: yellow / white-red-white / white-blue-white |
| Official cycling routes | swisstopo WMTS overlays Veloland (blue) and Mountainbikeland (orange) |
| Closures and detours | swisstopo WMS overlays, online only |
| Paper map 1:25 000 | swisstopo WMTS, or a local PMTiles file made by `tools/make-paper.py` |
| Routing | [BRouter](https://github.com/abrensch/brouter) (MIT) embedded as the `brouter` module, OpenStreetMap data from brouter.de (profiles `trekking` and `hiking-mountain`) |
| Search | GeoAdmin search API |
| Offline | MapLibre offline packs for the area on screen, zoom 8 to 15 |

Data © swisstopo, © SchweizMobil, © OpenStreetMap contributors.

## Build

    ./gradlew assembleDebug        # or assembleRelease, then sign

`local.properties` must point at an Android SDK. The `brouter` module is plain Java copied from
upstream (`brouter/UPSTREAM-COMMIT.txt`); the two profile files and `lookups.dat` are in `app/src/main/assets/brouter`.

## Data on the phone

- Routing: `Android/data/com.freedomfighter.swissbikehike/files/brouter/segments4/E5_N45.rd5` and `E10_N45.rd5`
  (about 450 MB), downloaded by the app over Wi-Fi on first **GO**, refreshed from the menu (brouter.de rebuilds them weekly).
- Paper map: optional `Android/data/com.freedomfighter.swissbikehike/files/paper/paper.pmtiles`, see `tools/make-paper.py`.
- Saved areas: MapLibre's own database in the app's private storage.
- Recorded tracks: `Documents/Swiss Bike-Hike Map/<date>_<mode>.gpx`.

## Weight of a full offline Switzerland (sampled 2026-10-28, bounding box of the country)

| Layer | Zoom | Size |
|---|---|---|
| Routing, both modes | | 450 MB |
| Vector base map | 6 to 14 | about 1.0 GB |
| Hillshade (relief tiles used by the style) | 6 to 14 | about 1.4 GB |
| Paper 1:25 000 | 8 to 15 | about 3.6 GB |
| Paper 1:25 000 | 8 to 16 | about 11 GB |

The box includes a good share of France, Italy, Germany and Austria; Switzerland alone is roughly half.
