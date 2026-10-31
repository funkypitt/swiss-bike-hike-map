#!/usr/bin/env bash
# Weekly health check of what the app depends on without a new build: the GitHub side (the
# scheduled workflows that rebuild the packs stop after 60 days without a commit; a failed run;
# a stale pack) and the third parties the app calls at run time (swisstopo style and tiles,
# GeoAdmin, transport.opendata.ch, brouter.de, the data sources of the packs).
#
#   tools/healthcheck.sh            prints the report; sends it to the Telegram bot when something is wrong,
#                                   and once a month when all is well (first week), so a dead cron is noticed
#   tools/healthcheck.sh --send     always sends
#   tools/healthcheck.sh --dry      never sends
#
# Needs: gh (logged in), curl, python3. The bot token and chat id are read from newspapers.sh.
set -u
REPO="funkypitt/swiss-bike-hike-map"
COMMIT_WARN_DAYS=45          # GitHub disables scheduled workflows after 60 days without a commit
PACK_WARN_DAYS=10            # the packs are rebuilt weekly
STYLE_URL="https://vectortiles.geo.admin.ch/styles/ch.swisstopo.basemap.vt/style.json"
UA="swiss-bikers-pedestrians-map healthcheck (+https://github.com/$REPO)"

problems=(); notes=()
bad() { problems+=("✗ $1"); }
ok()  { notes+=("✓ $1"); }

days_since() {  # ISO date or datetime -> days ago
  python3 -c 'import sys,datetime as d; t=sys.argv[1][:19].replace("Z",""); f="%Y-%m-%dT%H:%M:%S" if "T" in t else "%Y-%m-%d"; print((d.datetime.utcnow()-d.datetime.strptime(t,f)).days)' "$1" 2>/dev/null || echo 9999
}

http() {  # http METHOD URL [grep-pattern] -> ok/bad with the status code
  local method=$1 url=$2 pat=${3:-} code body
  if [ "$method" = HEAD ]; then
    code=$(curl -sS -o /dev/null -w '%{http_code}' -I -L -m 30 -A "$UA" "$url" 2>/dev/null); body=""
  else
    body=$(curl -sS -L -m 30 -A "$UA" "$url" 2>/dev/null); code=$?
    [ "$code" = 0 ] && code=200 || code="curl $code"
  fi
  if [ "$code" != 200 ] && [ "$code" != 206 ]; then bad "$4: HTTP $code"; return 1; fi
  if [ -n "$pat" ] && ! grep -q -- "$pat" <<<"$body"; then bad "$4: answers, but without « $pat »"; return 1; fi
  ok "$4"
}

# ---- GitHub: commits, workflows, runs, packs ----
if ! gh auth status >/dev/null 2>&1; then
  bad "gh is not logged in on this machine: nothing on GitHub could be checked"
else
  last=$(gh api "repos/$REPO/commits/master" --jq '.commit.committer.date' 2>/dev/null)
  if [ -z "$last" ]; then bad "GitHub: the last commit could not be read"; else
    age=$(days_since "$last")
    if [ "$age" -ge $COMMIT_WARN_DAYS ]; then bad "last commit $age days ago: GitHub disables the scheduled workflows at 60 days (push anything, or run them by hand)"; else ok "last commit $age days ago"; fi
  fi
  while IFS=$'\t' read -r name state file; do
    [ -z "$name" ] && continue
    if [ "$state" != active ]; then bad "workflow $name is $state (Actions tab → enable)"; continue; fi
    run=$(gh api "repos/$REPO/actions/workflows/$file/runs?per_page=1" --jq '.workflow_runs[0] | "\(.conclusion)\t\(.updated_at)"' 2>/dev/null)
    concl=${run%%$'\t'*}; when=${run#*$'\t'}
    case "$concl" in
      success) ok "workflow $name: last run succeeded $(days_since "$when") days ago" ;;
      null|"") ok "workflow $name: a run is in progress" ;;
      *) bad "workflow $name: last run $concl ($when)" ;;
    esac
  done < <(gh api "repos/$REPO/actions/workflows" --jq '.workflows[] | select(.name != "android") | [.name, .state, (.path | sub(".*/"; ""))] | @tsv' 2>/dev/null)
  for pack in transit-data:switzerland.transit places-data:switzerland.places; do
    rel=${pack%%:*}; name=${pack#*:}
    built=$(curl -sSL -m 30 "https://github.com/$REPO/releases/download/$rel/$name.json" 2>/dev/null | python3 -c 'import sys,json; print(json.load(sys.stdin).get("built",""))' 2>/dev/null)
    if [ -z "$built" ]; then bad "release $rel: $name.json unreadable"; else
      age=$(days_since "$built")
      if [ "$age" -gt $PACK_WARN_DAYS ]; then bad "pack $name built $age days ago (weekly expected)"; else ok "pack $name built $age days ago"; fi
    fi
  done
fi

# ---- third parties the app calls ----
# the swisstopo style: present, and still holding the layers the app anchors on and the source layers it reads
style=$(curl -sS -L -m 30 -A "$UA" "$STYLE_URL" 2>/dev/null)
if [ -z "$style" ]; then bad "swisstopo style: no answer"; else
  missing=$(python3 -c '
import sys, json
try: s = json.load(sys.stdin)
except Exception as e: print("not JSON"); sys.exit()
ids = {l.get("id") for l in s.get("layers", [])}
srcl = {l.get("source-layer") for l in s.get("layers", [])}
need_ids = ["housenumber", "transportation_label", "place_other"]
need_src = ["transportation", "poi"]
miss = [i for i in need_ids if i not in ids] + [f"source-layer {x}" for x in need_src if x not in srcl]
if "Frutiger Neue Regular" not in json.dumps(s): miss.append("font Frutiger Neue Regular")
print(", ".join(miss))
' <<<"$style" 2>&1)
  if [ -n "$missing" ]; then bad "swisstopo style changed: missing $missing"; else ok "swisstopo style (anchor layers, poi and transportation source layers, font)"; fi
fi
http HEAD "https://wmts.geo.admin.ch/1.0.0/ch.astra.veloland/default/current/3857/12/2130/1450.png" "" "swisstopo WMTS Veloland tile"
http HEAD "https://wmts.geo.admin.ch/1.0.0/ch.swisstopo.swisstlm3d-wanderwege/default/current/3857/12/2130/1450.png" "" "swisstopo WMTS hiking tile"
http GET "https://wms.geo.admin.ch/?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetCapabilities" "wanderland-sperrungen_umleitungen" "swisstopo WMS closures layer"
http GET "https://api3.geo.admin.ch/rest/services/api/SearchServer?type=locations&sr=4326&limit=1&searchText=Lausanne" '"lat"' "GeoAdmin search"
http GET "https://transport.opendata.ch/v1/locations?query=Lausanne" '"stations"' "transport.opendata.ch locations"
http GET "https://transport.opendata.ch/v1/connections?from=Lausanne&to=Montreux&limit=1" '"connections"' "transport.opendata.ch connections"
http GET "https://brouter.de/brouter/segments4/" "E5_N45.rd5" "brouter.de segments listing"
http HEAD "https://brouter.de/brouter/segments4/E10_N45.rd5" "" "brouter.de segment file"
http HEAD "https://download.geofabrik.de/europe/switzerland-latest.osm.pbf" "" "Geofabrik Switzerland extract"
# the portal refuses HEAD: ask for the first bytes of the feed instead
code=$(curl -sS -o /dev/null -w '%{http_code}' -L -m 60 -r 0-1023 -A "$UA" "https://data.opentransportdata.swiss/en/dataset/timetable-2026-gtfs2020/permalink" 2>/dev/null)
if [ "$code" = 200 ] || [ "$code" = 206 ]; then ok "opentransportdata.swiss GTFS permalink"; else bad "opentransportdata.swiss GTFS permalink: HTTP $code"; fi
http GET "https://search.ch/tel/api/?was=test&key=none" "" "search.ch directory endpoint (no key: any answer but a dead host)"

# ---- report ----
report="Swiss Bikers and Pedestrians Map · health check $(date +%Y-%m-%d)"
if [ ${#problems[@]} -gt 0 ]; then report+=$'\n'"${#problems[@]} problem(s):"$'\n'"$(printf '%s\n' "${problems[@]}")"; else report+=$'\n'"all good"; fi
report+=$'\n'"$(printf '%s\n' "${notes[@]}")"
echo "$report"

send=false
[ ${#problems[@]} -gt 0 ] && send=true
[ "$(date +%d)" -le 7 ] && send=true     # a monthly sign of life
[ "${1:-}" = --send ] && send=true
[ "${1:-}" = --dry ] && send=false
if $send; then
  tok=$(grep -o -E '[0-9]{8,10}:[A-Za-z0-9_-]{30,}' "$HOME/code/newspapers/scripts/newspapers.sh" | head -1)
  chat=$(grep -o -E 'TELEGRAM_CHAT_ID[^0-9]*[0-9]+' "$HOME/code/newspapers/scripts/newspapers.sh" | grep -o -E '[0-9]+$' | head -1)
  if [ -n "$tok" ] && [ -n "$chat" ]; then
    curl -sS -m 30 -X POST "https://api.telegram.org/bot$tok/sendMessage" --data-urlencode "chat_id=$chat" --data-urlencode "text=$report" >/dev/null || echo "telegram: send failed" >&2
  else echo "telegram: token or chat id not found" >&2; fi
fi
[ ${#problems[@]} -eq 0 ]
