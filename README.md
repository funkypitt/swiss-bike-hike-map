# Swiss Bike-Hike Map

A map for cycling and hiking in Switzerland, in the spirit of the old GPS boxes: one screen,
big buttons, the official hiking trails and cycling routes in their signpost colours on the
swisstopo map. Routing runs on the phone, with turn-by-turn guidance that goes on with the
screen off. No account, no tracking.

## Key points

* **BIKE or WALK**, chosen on the first screen; the map then shows the official network of that
  mode: yellow / white-red-white / white-blue-white hiking trails, blue Veloland and orange
  mountain-bike routes, closures and detours (© swisstopo, © SchweizMobil).
* **Stops**: a long press, a search result or a place shared by another app puts a numbered stop
  on the map; **GO** routes through all of them on the phone (BRouter on OpenStreetMap data,
  about 450 MB fetched once over Wi-Fi), with distance, climb, descent, time and an elevation
  profile.
* **NAV**: one big arrow, the distance to the turn, the next one, the time of arrival; a
  vibration before each turn; a new route when you leave the line; the trip goes on in a
  notification with the screen off or another app in front, and is kept in a local history.
* **GPX**: follow a track as it is (turns read off its shape), or record your own.
* **FIND**: places and addresses (GeoAdmin), typed coordinates (WGS84, LV95, LV03), people and
  firms from the search.ch directory with your own free key, and your favourites, which you can
  import and export (GPX, KML, GeoJSON, CSV).
* **Offline**: save the area on screen or the whole country in one button (about 2.5 GB); the
  download survives the app being closed and the phone restarting, waits for Wi-Fi, and the
  saved areas refresh themselves once a week.
* **Night map** by hand or after sunset; share a place or your position as text; six languages
  (en, fr, de, es, pt, ru).
* Permissions: location, notifications (guidance and downloads), vibration; the network serves
  only the map tiles, the routing data and the searches you ask for.

Data and sizes, the shape of the code and what was measured: [docs/NOTES.md](docs/NOTES.md).

## Install

- **F-Droid** (recommended, updates arrive by themselves): add the repository from [gallaz.ch/apps](https://gallaz.ch/apps/#fdroid), or the address `https://funkypitt.github.io/fdroid-repo/repo` in F-Droid.
- **Obtainium**: add `https://github.com/funkypitt/swiss-bike-hike-map` in Obtainium.
- **APK**: attached to the [latest release](../../releases/latest). No automatic updates.

All three deliver the same file, with the same signature. Android 9 or newer, arm64.

## Build

```
./gradlew assembleRelease
```

`local.properties` must point at an Android SDK. The `brouter` module is BRouter's routing core
(MIT) copied from upstream, see `brouter/UPSTREAM-COMMIT.txt`; the profiles and `lookups.dat`
are in `app/src/main/assets/brouter`.

## Credits

Map: swisstopo vector base map and style, hiking trail network, Veloland and Mountainbikeland
routes, closures — © swisstopo, © SchweizMobil, free open government data.
Routing: [BRouter](https://github.com/abrensch/brouter) (MIT) on OpenStreetMap data (ODbL),
segments from brouter.de. Rendering: [MapLibre Native](https://maplibre.org) (BSD).
Search: GeoAdmin API; people and firms: search.ch directory (Swisscom Directories AG) with the
user's own key.
