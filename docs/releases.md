# Release notes

One entry per build placed in `apk/`, newest first. Each names the APK, the
commit it was built from, and what changed in terms of what the unit does —
implementation detail belongs in the commit message.

Builds are debug-signed and installed by hand on the unit. A build exists
because something was worth testing in the car, so entries are written for
someone about to sit in the driver's seat and check.

---

## 20260816-1216 — `a216be2`

**Map zoom is a setting.** Settings › Map › *Map Zoom*, from z16 to z20. The
default moves to z18: the tiles stop at z15, so past that it is the same
geometry drawn larger, and the choice is really about how much road you want
visible ahead.

**Roads and labels larger again** — road widths up by about a third, street
names, place names and POI names with them, and a wider halo behind the text.

**Reclaim Screen removed.** It fought another application for the foreground
and was never going to be clean. The cleaner routes are the factory menu
(**3368**) if it offers an auto-display option, or disabling the single vendor
activity over ADB.

---

## 20260815-1838 — `00cbb08`

**Take the screen back from the car's own UI.** Settings › Permissions ›
*Reclaim Screen*, off by default.

The original car screen appearing on ignition was never a home-role problem.
The vendor application answers a CAN signal by opening its car-information
screen over whatever is showing. Nothing without system privileges can stop it
starting, so the launcher asks for the foreground back instead — rate limited,
and deliberately never for the reversing camera.

Check *Default Home* first. It shows the same symptom for a different reason,
and it is the one actually fixable.

---

## 20260810-1818 — `70eab1e`

**Volume identified.** It sits on the sound module at id 2, now taken from the
vendor's own constants rather than from where it happened to appear. Mute and
audio source come with it.

**OBD module opened for the first time.** Nothing reads a value from it — no
published source names its ids. What it reports is written to
`vendor/syu-modules.txt`. Silence there closes the fuel question for good;
anything else is the last route to a fuel level without a dongle.

*To test: drive a few minutes with the engine running, then send that file.*

---

## 20260809-1821 — `b77dab3`

**Dark map fixed.** Both palettes were written to one file, so switching between
day and night rewrote a style the renderer had already loaded and nothing
changed until the launcher restarted. Each palette now has its own file.

**POI labels enlarged.** They are injected from code rather than the style
asset, so the previous pass missed them and they alone stayed at ten pixels.

---

## 20260809-1809 — `24e8499`

**Wider roads, larger street names.** The names were fixed at eleven pixels
regardless of zoom — a size chosen for a scale this launcher never opens on.
They now grow with the zoom like everything else in the style.

---

## 20260809-1806 — `725e675`

**Fuel widget becomes a lamp.** This car sends no level, so the widget stops
saying so in words: dark normally, amber with RESERVE when the car raises its
own low-fuel warning.

---

## 20260809-1803 — `b9a4d99`

**Fuel is settled, and the answer is no.** Id 106 delivers zero, always. The
decoder declares one fuel field and the Evoque never fills it. Zero is refused
as a reading rather than shown — a gauge reading empty on a quarter tank is
believed, which makes it worse than a blank one.

---

## 20260809-1743 — `2085556`

**The common id block was never being read.** Ids fall into two families: per-car
signals below 256, and a block from 1000 up common to every CAN box. Only the
first was subscribed. The second holds whether the car has an outside sensor at
all, and a car-independent speed and engine speed.

---

## 20260809-1733 — `61b4cd3`

**Temperature arrives at startup.** It was never missing, only late: the decoder
sends on change, so a parked car in settled weather says nothing. The last
reading is now kept across restarts and shown until three hours old.

Registration also asks for the current value, confirmed against a working
independent implementation of the same interface.

---

## 20260809-1650 — `ead1efd`

**Map glides instead of juddering.** Each fix was starting a fresh eased
animation, cutting the previous one mid-flight and restarting from a standstill
— once a second, exactly the stutter. The camera now closes on its target every
frame with no beginning or end to interrupt.

**Fuel read as litres**, not a percentage. **Temperature rounded** to whole
degrees.

---

## Earlier

Before this file existed. See the commit log, and
[vehicle-data.md](vehicle-data.md) for what was established about the car rather
than about the launcher.
