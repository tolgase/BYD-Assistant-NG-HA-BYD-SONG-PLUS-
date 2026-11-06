#!/usr/bin/env python3
"""Checks which BYD Assistant NG vehicle commands exist on the connected head unit (e.g. a BYD Song PLUS)
and writes a compatibility report plus a trimmed command registry for that car.

Everything it runs on the car is READ-ONLY (the Compat probe never calls set()).

    # 1. once: build and push the probes (Compat.java must sit in tools/probes/)
    ANDROID_HOME=... tools/probes/build.sh
    # 2. with the car parked and `adb devices` showing the head unit:
    python3 tools/songplus_compat.py --car "Song PLUS DM-i 2023"

Outputs (in --out, default ./songplus-compat/):
    compat_raw.tsv                   the probe's raw answer
    compat_report.md                 what works, what is missing, what needs a decision
    vehicle_commands.<slug>.json     the registry with unsupported commands removed
                                     (copy it over app/src/main/resources/vehicle_commands.json to build a port)
"""
import argparse
import datetime
import json
import pathlib
import re
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
REGISTRY = ROOT / "app/src/main/resources/vehicle_commands.json"
DEX = "/data/local/tmp/probes.dex"
REMOTE_IDS = "/data/local/tmp/compat_ids.txt"

NO_VALUE = {"-10011", "65535"}          # "unsupported" / "not fitted" on the Leopard 5
VOICE_STREAM_RANGE = "min=5 max=24"     # VoiceChannel.EXPECTED_MIN_INDEX / EXPECTED_MAX_INDEX on the Leopard 5


def adb(*args, check=True):
    r = subprocess.run(["adb", *args], capture_output=True, text=True)
    if check and r.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed:\n{r.stderr or r.stdout}")
    return r.stdout


def ids_for(cmds):
    """(deviceType, featureId, label) for every id the registry writes or reads."""
    out = []
    for c in cmds:
        dt = c.get("deviceType")
        if dt is None:
            continue
        for key in ("featureId", "stateFeatureId"):
            if c.get(key) is not None:
                out.append((dt, c[key], f"{c['id']}:{key}"))
        req = c.get("requires") or {}
        if req.get("featureId") is not None:
            out.append((dt, req["featureId"], f"{c['id']}:requires"))
        for o in c.get("overrides") or []:
            out.append((dt, o["featureId"], f"{c['id']}:override"))
    seen, uniq = set(), []
    for t in out:
        if (t[1], t[2]) not in seen:
            seen.add((t[1], t[2]))
            uniq.append(t)
    return uniq


def parse(raw):
    env, classes, services, streams, names, rows = {}, {}, {}, [], [], {}
    for line in raw.splitlines():
        p = line.split("\t")
        kind = p[0]
        if kind == "ENV":
            env[p[1]] = p[2] if len(p) > 2 else ""
        elif kind == "CLASS":
            classes[p[1]] = p[2]
        elif kind == "SERVICE":
            services[p[1]] = p[2]
        elif kind == "STREAM":
            streams.append(" ".join(p[1:]))
        elif kind == "NAME":
            names.append(p[1:])
        elif kind == "ID":
            label, dt, fid, nm, val = p[1:6]
            rows[label] = {"deviceType": int(dt), "id": int(fid), "names": nm, "value": val}
        elif kind == "FAILED":
            sys.exit("probe failed on the car: " + line)
    return env, classes, services, streams, names, rows


def verdict(cmd, rows, services):
    """SUPPORTED / LIKELY / NOT_FITTED / MISSING / CHECK, with a reason."""
    inv = cmd["invocation"]["type"]
    cid = cmd["id"]
    if inv == "streamVolume":
        return "SUPPORTED", "plain Android media stream"
    if inv == "acBinderProperty":
        if services.get("byd_airconditioning") != "yes":
            return "MISSING", ("no `byd_airconditioning` service: needs a code change — map CLIMATE to "
                               "BYDAutoAcDevice in ReflectionVehicleController.GENERIC_ROUTE_CLASS_BY_DOMAIN "
                               "and switch this command to genericFeatureSet")
        return "LIKELY", "A/C service present; property ids (101…114) must be confirmed from logcat `PropertyValue{…}`"
    if inv == "binderCommand":
        svc = cmd["invocation"]["serviceName"]
        return ("LIKELY", f"`{svc}` present; command codes unverified") if services.get(svc) == "yes" \
            else ("MISSING", f"no `{svc}` service on this car")
    if inv == "namedMethod":
        return "CHECK", f"calls `{cmd['invocation']['methodName']}` by name; run `Members` on the setting device to confirm it exists"

    ctrl = rows.get(f"{cid}:featureId")
    state = rows.get(f"{cid}:stateFeatureId")
    if ctrl is not None and ctrl["names"] == "-":
        return "MISSING", f"control id {ctrl['id']} has no name in this car's framework (renumbered or absent)"
    if state is not None:
        if state["names"] == "-":
            return "MISSING", f"state id {state['id']} has no name in this car's framework"
        if state["value"] in NO_VALUE:
            return "NOT_FITTED", f"state `{state['names']}` reads {state['value']}"
        if state["value"].startswith("ERR"):
            return "CHECK", f"state read failed ({state['value']})"
        return "SUPPORTED", f"`{state['names']}` = {state['value']}"
    if ctrl is not None:
        return "LIKELY", f"control `{ctrl['names']}` exists; no state id to read back — test it on the car"
    return "CHECK", "no id to look up"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--car", default="BYD Song PLUS", help="label used in the report and file name")
    ap.add_argument("--out", default="songplus-compat")
    ap.add_argument("--raw", help="re-use a saved compat_raw.tsv instead of asking the car")
    args = ap.parse_args()

    cmds = json.loads(REGISTRY.read_text(encoding="utf-8"))
    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    if args.raw:
        raw = pathlib.Path(args.raw).read_text(encoding="utf-8")
    else:
        if "probes.dex" not in adb("shell", "ls", DEX, check=False):
            sys.exit("probes.dex not on the car: run tools/probes/build.sh first (with Compat.java in tools/probes)")
        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as f:
            for dt, fid, label in ids_for(cmds):
                f.write(f"{dt} {fid} {label}\n")
        adb("push", f.name, REMOTE_IDS)
        raw = adb("shell", f"CLASSPATH={DEX} app_process /system/bin Compat {REMOTE_IDS}")
        adb("shell", "rm", REMOTE_IDS, check=False)
    (out / "compat_raw.tsv").write_text(raw, encoding="utf-8")

    env, classes, services, streams, names, rows = parse(raw)
    results = [(c, *verdict(c, rows, services)) for c in cmds]

    slug = re.sub(r"[^a-z0-9]+", "-", args.car.lower()).strip("-")
    keep = {"SUPPORTED", "LIKELY", "CHECK"}
    trimmed = [c for c, v, _ in results if v in keep]
    (out / f"vehicle_commands.{slug}.json").write_text(
        json.dumps(trimmed, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    icon = {"SUPPORTED": "✅", "LIKELY": "🟡", "CHECK": "🔍", "NOT_FITTED": "⛔", "MISSING": "❌"}
    lines = [f"# BYD Assistant NG — compatibility on {args.car}", "",
             f"Generated {datetime.datetime.now():%Y-%m-%d %H:%M} by tools/songplus_compat.py (read-only probe).", "",
             "## 🚗 Head unit", ""]
    lines += [f"- **{k}**: {v}" for k, v in env.items()]
    abis = env.get("abis", "")
    if abis and "arm64-v8a" not in abis:
        lines.append("- ⚠️ **No arm64-v8a ABI** — the APK is built arm64-only (`abiFilters`); add `armeabi-v7a`.")
    sdk = int(env.get("sdk", "0") or 0)
    if sdk and sdk < 33:
        lines.append(f"- ⚠️ Android API {sdk}: the project was only tested on Android 13 (API 33).")
    lines += ["", "## 🧩 Framework classes and services", ""]
    lines += [f"- {'✅' if v == 'yes' else '❌'} `{k}`" for k, v in classes.items()]
    lines += [f"- {'✅' if v == 'yes' else '❌'} service `{k}`" for k, v in services.items()]
    lines += ["", "## 🔊 Audio streams", "", "Voice channel expects stream 17 with the Leopard's range "
              "(see `VoiceChannel.EXPECTED_MIN_INDEX/MAX_INDEX`); a different range disables it.", ""]
    lines += [f"- {s}" for s in streams]
    voice = next((s for s in streams if s.startswith("17 ")), "")
    if voice and VOICE_STREAM_RANGE not in voice.replace("\t", " "):
        lines.append(f"- ⚠️ Stream 17 range differs from the Leopard's ({VOICE_STREAM_RANGE}): the voice channel "
                     "will switch itself off unless `VoiceChannel.EXPECTED_MIN_INDEX/MAX_INDEX` are adapted.")
    lines += ["", "## 🧪 Features the app checks by name", ""]
    for n in names:
        lines.append(f"- `{n[0]}` → {n[1]}" + (f" ({n[2]})" if len(n) > 2 else ""))
    counts = {}
    for _, v, _ in results:
        counts[v] = counts.get(v, 0) + 1
    lines += ["", "## 🎛️ Vehicle commands", "",
              " · ".join(f"{icon[k]} {k} {counts.get(k, 0)}" for k in icon), "",
              "| | Command | Verdict | Why |", "| --- | --- | --- | --- |"]
    order = list(icon)
    for c, v, why in sorted(results, key=lambda r: (order.index(r[1]), r[0]["id"])):
        lines.append(f"| {icon[v]} | `{c['id']}` — {c.get('label', c['displayName'])} | {v} | {why} |")
    lines += ["", "## ▶️ Next steps", "",
              "- ❌ MISSING: search this car's names with `Feat '<KEYWORD>' read` (e.g. `WINDOW`, `SEAT_HEAT`, "
              "`SUNROOF`) and re-map the id in the registry if the control exists under another number.",
              "- 🟡 LIKELY / 🔍 CHECK: confirm with `Watch . 120 0` while operating the control on the native screen, "
              "then one test write while parked.",
              "- ⛔ NOT_FITTED: the car reports the option as absent; leave it out.",
              f"- Build the port with `vehicle_commands.{slug}.json` copied over `app/src/main/resources/vehicle_commands.json`, "
              "then run `python3 tools/gen_command_docs.py`."]
    (out / "compat_report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{len(trimmed)}/{len(cmds)} commands kept → {out}/vehicle_commands.{slug}.json")
    print(f"report → {out}/compat_report.md")


if __name__ == "__main__":
    main()
