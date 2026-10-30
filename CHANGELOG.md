# Changelog

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
