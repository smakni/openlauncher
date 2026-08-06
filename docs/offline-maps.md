# Offline maps

The map widget renders vector tiles with MapLibre. It can take them from two
kinds of source, and which one you choose decides whether the launcher can
download regions by itself.

| Source | Offline | Download from the launcher | Needs |
|---|---|---|---|
| PMTiles archive | Yes, fully | No | A file prepared on a computer |
| Tile URL (ZXY) | Yes, per region | Yes | An API key |

Both give you a map that works with no connection. The difference is only in how
the data gets onto the unit.

---

## Why the launcher cannot download a PMTiles region

MapLibre's region downloader works by walking a list of per-tile URLs. A PMTiles
archive has none: it is a single file addressed by byte range, so there is
nothing for the downloader to enumerate. Asking it to try reports one required
resource — the style — and no tiles behind it.

This is a property of the format, not a bug to be fixed. A PMTiles region has to
be cut out of the planet file with the `pmtiles` tool, which is what the manual
route below does.

---

## Route A — PMTiles archive, prepared on a computer

Best if you want one region available permanently and do not mind a one-off
five minutes at a computer.

### 1. Install the tool

Download the `pmtiles` binary for your platform from
<https://github.com/protomaps/go-pmtiles/releases> and put it on your PATH.

### 2. Find the current planet build

Protomaps publish a build a day and retire old ones, so the date in the URL
matters. The list is at <https://maps.protomaps.com/builds>.

### 3. Cut out your region

```
pmtiles extract https://build.protomaps.com/20260801.pmtiles paris.pmtiles \
  --bbox=2.20,48.78,2.47,48.92 \
  --maxzoom=15
```

`--bbox` is `west,south,east,north` in degrees. Draw one at
<https://boundingbox.klokantech.com> and copy the CSV form.

`--maxzoom` is the lever on file size: each level roughly quadruples it. 14 is
enough to see which street you are on, 15 shows building outlines, and 16 is
rarely worth the size in a car.

The extract only downloads the byte ranges your box covers, so this is quick
even though the planet file is 120 GB.

### 4. Copy it to the unit

Put the `.pmtiles` file on a USB stick, plug it into the head unit, then:

**Settings › Maintenance › Offline Map Archive** → pick the file.

It is copied into the launcher's own storage, so the stick can be removed.

### 5. Add the widget

**Home › edit mode › add widget › MAP**

An installed archive is used ahead of the remote build, so the map now works
with no connection at all.

---

## Route B — Tile URL, downloaded by the launcher

Best if you would rather not touch a computer, and are willing to sign up for a
key.

### 1. Get a key

Protomaps' hosted API is free for non-commercial use. Sign up at
<https://protomaps.com/api> and copy the key.

### 2. Point the launcher at it

**Settings › Maintenance › Tile URL** and paste the template, with your key:

```
https://api.protomaps.com/tiles/v4/{z}/{x}/{y}.mvt?key=YOUR_KEY
```

Setting this switches the map away from PMTiles. The braces are placeholders
MapLibre fills in per tile — leave them exactly as they are.

### 3. Download a region

**Settings › Maintenance › Offline Map Radius**, then **Download Map Around
Me**, with a GPS fix and a connection. Progress shows as a percentage and a
tile count.

The area is stored in MapLibre's own database and served ahead of the network
from then on.

---

## Which to use

Route A costs one session at a computer and then never asks anything again.
Route B needs no computer but ties the map to a hosted service and a key.

Route A also has no ceiling on area: a whole country as PMTiles is smaller than
a city downloaded tile by tile, because the archive is packed rather than stored
as individual tiles.

---

## Fonts

Labels are drawn from glyphs bundled in the APK, so street and place names work
offline on either route. Only the Latin ranges are included — a name in Cyrillic
or Chinese will come out blank rather than breaking the map.
