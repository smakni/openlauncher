# Vehicle data on this unit

What the launcher reads from the car, where each reading comes from, and which
questions are settled rather than still open.

Written against one specific installation: a **2013 Range Rover Evoque 2.2 TD4**
with an aftermarket FYT/SYU head unit (Unisoc UMS512, Android 10) and a **ZHTD**
CAN decoder. Almost none of the identifiers below transfer to another car — the
reason why is the first section.

---

## Where the numbers come from

The head unit ships a vendor service, `com.syu.ms`, which talks to the CAN
decoder over I2C and exposes it over Binder. The launcher binds to it directly.

| Step | Value |
|---|---|
| Bind action | `com.syu.ms.toolkit` — the action, **not** the component name |
| Interface | `com.syu.ipc.IRemoteToolkit` → `getRemoteModule(7)` for CAN |
| Subscribe | `IRemoteModule.register(callback, id, 1)` |
| Receive | `IModuleCallback.update(id, ints, floats, strings)` |

Two details cost days each and are worth stating plainly.

**`register` fires only when a value changes.** Nothing is pushed on
subscription unless the third argument is `1`. A value the car has been holding
steady — outside temperature on a parked car — therefore never arrives at all
for a launcher that started afterwards.

**`get()` does not work on the CAN module.** It exists on the interface and
answers nothing for every data id tried. The vendor's own application never
calls it for these either: it keeps a local `DataCanbus.DATA[]` array filled
entirely by callbacks and reads that. Subscription is the only way in.

### Why there is no published id table

The platform ships roughly 2500 CAN boxes across some 600 decoder classes, and
each class declares its own identifiers. A table for one unit says nothing about
another, which is why nobody has written one down. The ids below were read out
of the class this unit actually runs: `Callback_0450_ZHTD_BWM_CarUi`, the single
ZHTD class among 10,108, confirmed by the `U_LANDROVER_*` constants it carries.

---

## What the car reports

| Reading | Id | State |
|---|---|---|
| Engine speed | 107 | Works. Confirmed at 813 then 794 on a parked diesel idle |
| Vehicle speed | 105 | Works. Wheel speed — see the note below |
| Outside temperature | 123 | Works. Half degrees, `raw / 2 - 40` |
| Gear | 131 | Arrives; raw values 2 and 4 seen, mapping not established |
| Low fuel warning | 163 | Arrives |
| Handbrake, lights, indicators | 104, 98–101 | Arrive; not displayed |
| **Fuel level** | **106** | **Always zero. See below** |
| Volume | 137 | Never sent by this car |

Ids below 256 are per-car, named by the decoder class. A second block from 1000
up is common to every CAN box and carries capability flags plus a car-independent
speed and engine speed — `U_EXIST_TEMP_OUT` (1012), `U_CUR_SPEED` (1031),
`U_ENGINE_SPEED` (1032). Both ranges are subscribed; the common block is used
only where the per-car id says nothing, since a class naming an id for this
specific car is the better authority.

### Fuel: settled, and the answer is no

The decoder declares exactly one fuel field across its whole range,
`U_CAR_OIL_REMAINED = 106`, formatted by the vendor's own screen as `"%d L"` —
litres, unscaled. This car writes zero to it, permanently. The field exists in
the ZHTD protocol and the Evoque's bus never fills it.

Zero is refused as a reading rather than displayed. A car with no fuel is not
running, so a genuine zero would never be on screen either, and a gauge showing
empty on a quarter tank is worse than one showing nothing — the first is
believed. The widget falls back to a lamp: unlit normally, amber when the car
raises its own reserve warning on id 163.

A numeric level would need an OBD dongle, which reports PID `2F` directly from
the ECU and bypasses the CAN box entirely. That code path is already written.

### Speed: the launcher and the dashboard disagree, correctly

Regulation requires an indicated speed never to read *below* true speed; the
tolerance is one-sided, up to +10% +4 km/h. Manufacturers therefore calibrate
deliberately high, typically 5–8%. The CAN carries wheel speed without that
margin, so at an indicated 100 the launcher shows about 93–95. A GPS speed
agrees with the launcher, not with the instrument cluster.

---

## Reading it back

Two diagnostics ship in the app and write to
`Android/data/com.openlauncher.app/files/vendor/`.

`syu-signals.txt` — what each id answers, in words. It distinguishes a refused
transaction from one accepted and answered empty from one carrying a value,
which are three different faults that look identical on a blank widget.

`syu-probe.txt` — the sweep behind Live Vehicle Values, which registers every id
across every module and records a timeline. Arm a capture, perform one action,
stop: whatever moved inside the window is the answer.

Developer access on this unit: **8888** opens the CANbus menu.

---

## Still open

- **Gear value in reverse.** The signal already arrives; only its value for
  reverse is unknown. That is what would let the launcher switch to the camera
  by itself.
- **Module 12 is the OBD module** and has never been opened. If a fuel level
  exists anywhere on this unit without a dongle, it is there.
- **Volume.** Not on CAN. A candidate sits at module 4, id 2 — found by where it
  appeared rather than by name, so it needs confirming by turning the knob.
- **Reversing camera** black screen, likely a ZHTD protocol variant. AHD versus
  CVBS untested.

---

## Sources

The vendor's own decompiled sources and an independent implementation of the
same interface, both of which settled questions that guessing had not:

- [vasyl91/FYT-Launcher-Mod](https://github.com/vasyl91/FYT-Launcher-Mod) —
  decompiled `com.syu` sources, including `IRemoteModule`, `ModuleObject`,
  `FinalCanbus` and every `Callback_*` decoder class
- [AxesOfEvil/FYTCanbusMonitor](https://github.com/AxesOfEvil/FYTCanbusMonitor) —
  a working Kotlin library for this interface; confirms `register(..., 1)` and
  documents the common id block to 1036
- [XDA — Developing an OBD2/Canbus data logger](https://xdaforums.com/t/developing-an-obd2-canbus-data-logger.4454275/)
