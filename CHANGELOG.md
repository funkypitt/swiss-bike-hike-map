# Changelog

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
