# Changelog

## 0.11.2 — 2026-11-05

* The closures and diversions of SwitzerlandMobility are sharp at every zoom: they were an
  enlarged image of zoom 14 (a thick, blurry line across the houses that looked hand-drawn);
  they are now asked up to zoom 18 at screen resolution and follow the streets.
* A tap on a closure or a diversion (BIKE and WALK) opens its notice: kind, place, reason,
  duration, the official text, the source, and a link to the SwitzerlandMobility page.
* The BIKE and WALK legends name them: diversions thick amber, closures red.

## 0.11.1 — 2026-11-05

* The timetable (first step of Switzerland offline) no longer stalls when GitHub is slow: its
  description file is asked patiently (four tries, longer waits) and, when GitHub's direct link
  answers 504, through GitHub's API instead (seen for hours on 2026-11-05).
* A part that fails for now no longer holds up the others: the job goes on with the next part
  and comes back to it; after a failure the job retries every 1, 2, 3… minutes instead of
  doubling the wait each time.

## 0.11.0 — 2026-11-05

* Offline downloads can use mobile data when you allow it: MENU › ALSO OVER MOBILE DATA (off
  by default; the size of Switzerland offline is said before it is switched on). Never while
  roaming. Off Wi-Fi, the download and update dialogs offer OVER MOBILE DATA directly; a job
  already waiting for Wi-Fi starts at once when mobile data is allowed. The dialogs and the
  waiting line say which network will be used.

## 0.10.0 — 2026-11-05

* Offline is now one option: **Switzerland offline**, at the top of the menu — map, routing,
  places and timetable of the whole country (about 2.9 GB) in one download. Saving the area on
  screen is gone; areas saved by older versions can be deleted in one button and are dropped
  once the whole map is in.
* One download manager for all of it: a progress bar in the menu and in one notification with
  PAUSE; it waits for Wi-Fi, resumes exactly where it stopped after a lost connection, a closed
  app or a restart (files by byte ranges, the map by the tiles it still lacks), and stops with
  the reason when the phone has not the room. A paused download never restarts by itself; it can
  be resumed or cancelled (what is complete stays).
* Updates fit daily use: the timetable is worth fetching every week, places and routing every
  month, the map only when swisstopo or ASTRA publish a new one — then every tile is only
  checked and just the changed ones fetched. One small request per part tells whether the server
  has something newer; UPDATE fetches only what is due. A line on the first screen says when an
  update is worth it (from the dates on the phone, no network).
* The offline map no longer carries the closures layers (they change daily and only make sense
  live): 1.25 M resources instead of 1.37 M.

## 0.9.1 — 2026-11-03

* Stopped in navigation, the map no longer spins round: standing still, the course the GPS
  gives and the few metres the position wanders point anywhere. Below riding pace (6.5 km/h on
  the bike, 3 km/h on foot) the last course taken while moving is kept.
* The route to follow is much thicker and stands out more: magenta over a dark edge and a white
  casing, wider as the map zooms in (17 px at street level instead of 6).

## 0.9.0 — 2026-11-03

* Navigation: the arrow sits low on the screen again, three quarters of the way down the map
  under the panels, so what is seen is the way ahead. The map's old padding call only moved the
  compass in MapLibre 13; the camera now gets its own padding.
* Search around you like a city map: cafés, bars, fast food, shops (what they sell, in your
  language), post offices, banks and hairdressers join the places (183 000 places, 5 MB pack);
  a word also finds a cuisine or a kind of shop around (« pizza », « sushi », « fleuriste »).
  SHOW ON THE MAP draws the list as numbered red dots; × clears them.
* A tap on a place (in the list, on a result dot, on a place dot, or on the pinned place's
  name) opens its card: open or closed now (« Open · closes at 19:00 »), the week's hours,
  address, phone, e-mail, website, position and distance; CALL (the phone app, number typed in),
  CONTACT (the contacts app's new-contact form, filled in: name, phone, e-mail, address,
  website, hours in the notes), COPY, WEBSITE, SHARE, SEARCH.CH (completes it with your key),
  PIN. People and firms of the directory get the same card (ON THE MAP places them).
* The list rows say whether a place is open now.

## 0.8.2 — 2026-11-02

* The position dot appears indoors too. The map listened to the satellites only, which say
  nothing inside a building (and forget the last fix after a reboot); it now also takes the
  phone's own position (fused / network) until the satellites speak, then lets them rule.
  « Looking for your position… » is shown until the first point; the share-position and the
  navigation start use the freshest point of any provider too. Precise and approximate location
  are asked together; approximate is enough for the dot. Location switched back on while the
  map is open is heard again.

## 0.8.1 — 2026-10-30

* A ◆ button between the mode badge and FIND turns the places on the map on and off (black
  when on). Without the pack on the phone, it offers the download (3 MB, Wi-Fi). The menu keeps
  its own switch beside the download and the colour legend.

## 0.8.0 — 2026-10-30

Places from OpenStreetMap, offline, and the directory to complete them.

* A pack of places of Switzerland built weekly from OpenStreetMap (`tools/make-places.py`,
  pyosmium, Geofabrik extract; 142 000 places, 3 MB to download): drinking water, fountains,
  toilets, shelters, mountain huts, picnic and fire places, viewpoints, bathing, bike shops,
  repair stations, rentals, parkings, e-bike charging, sports shops, bakeries, groceries and farm
  shops, restaurants, campsites, hotels, pharmacies, hospitals, tourist offices, sights, cash
  machines. Fetched from MENU › PLACES, part of UPDATE ALL OFFLINE DATA; nothing without you.
* FIND › PLACES opens on the categories: one tap lists what is around you (or around the map's
  centre), nearest first, with hours, altitude, phone, address when OpenStreetMap has them. A
  typed word searches the categories, GeoAdmin and the names of the pack together; coordinates
  still work.
* A long press on a place shows its details: PIN, CALL, WEBSITE, or SEARCH.CH when a directory
  key is there and the place has no phone: the directory's entries for that name and town
  complete the row (phone, address). With a key, Search in PLACES also merges the directory's
  firms into the list: those naming a listed place complete it, the others are appended.
* MENU › PLACES ON THE MAP draws the places as coloured dots from zoom 13 (blue water, green
  rest, red bike, amber food, purple lodging, grey services), names close up; a tap on a dot
  pins it. Off by default.
* Data packs share one download mechanism (`DataPack`): the timetable and the places.
* Credits: © OpenStreetMap contributors on the map's attribution line, in ABOUT and the README.

## 0.7.0 — 2026-10-29

Fixes from a critical review of the 0.6 series.

* Public-transport guidance: a ride that left without you is now detected (at the stop or still
  walking) and a new journey planned; without one, the board stays and the search is retried a
  minute later. The guidance is a pure, unit-tested state machine (`TransitGuide`).
* GO on a planned itinerary while a navigation runs switches in place (the previous trip goes to
  the history) instead of stopping and restarting the service, which could leave GPS and a wake
  lock running.
* Offline timetable: tomorrow's services are considered (journeys and departures around
  midnight), the change time at a station is kept whatever the round that reached it, the two
  daylight-saving days no longer shift the times by an hour.
* Live timetable: the date and time are sent in Swiss time whatever the phone's zone; a failure
  on one stop pair is reported instead of "no journey".
* Journeys whose routed first walk would start before the asked departure are dropped.
* The timetable pack is unpacked on a worker thread, right after the download completes, with its
  description fetched beforehand (no more perpetual "newer on the server").
* Route planner: the form belongs to its map and is cleared when the planner closes (a stale date
  no longer sticks), the chosen journey's own departure is kept, the map's "pick a point" state
  survives rotation and is cleared when not used.
* GO on the map always computes; the pin strip's × clears; SHARE moved off the pin strip (tap the
  pin's name to share it); only the navigation shows STOP.
* Departures: "in N min" and a real-time mark; one answer per stop kept 30 s (the API limits the
  rate).
* On board a train or bus the GPS slows to one fix every five seconds.
* Privacy wording: the transit map sends your position to transport.opendata.ch to find the stops
  around you; said in the README, the F-Droid description and ABOUT.
* Continuous integration: unit tests and a debug build at every push.

## 0.6.5 — 2026-10-29

* The walking map shows the public-transport stops as plain dots.

## 0.6.3 / 0.6.4 — 2026-10-29

* PLAN on every map: start, destination, date and time, detailed itinerary, share, keep, show, GO.
* A tap on a stop dot lists its lines with their next departure.
* Buttons no longer shifted by baseline alignment (the badge's missing border).

## 0.6.2 — 2026-10-29

* BRouter profiles tuned for the Swiss signposted networks.
* Download states and failures in words; live-timetable failures reported; route failures explained.

## 0.6.1 — 2026-10-29

* Nothing downloads by itself any more; UPDATE ALL OFFLINE DATA at the top of the menu.

## 0.6.0 — 2026-10-29

* Public transport as a third mode; the app becomes Swiss Bikers and Pedestrians Map.

## 0.5.1 — 2026-10-28

* First public release (Swiss Bike-Hike Map).
