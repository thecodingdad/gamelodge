#!/usr/bin/env python3
"""
Builds app/src/main/assets/emulators.json, the launcher's catalog of Android emulators and how to
start a ROM with each of them.

The start commands and emulator packages come from ES-DE (https://es-de.org), whose Android
configuration is maintained and tested by its community (MIT licence, see the notice in the
generated file). Scanning (folders, file extensions) uses our own curated table below, with the
platform names of the Moonlight Launcher Companion so ROMs merge with host entries.

Usage: python3 tools/emulators/generate_catalog.py [es_systems.xml es_find_rules.xml]
Without arguments the current files are downloaded from the ES-DE repository.
"""
import json
import os
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET

BASE = "https://gitlab.com/es-de/emulationstation-de/-/raw/master/resources/systems/android/"
OUT = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "assets", "emulators.json")

# id, platform name (as in the companion), folder names, scan extensions, ES-DE systems, experimental
PLATFORMS = [
    ("gc", "Nintendo GameCube", ["gc", "gamecube", "ngc"], [".rvz", ".iso", ".gcm", ".gcz", ".ciso", ".wia", ".m3u"], ["gc"], False),
    ("wii", "Wii", ["wii"], [".rvz", ".wbfs", ".iso", ".wia", ".ciso", ".wad"], ["wii"], False),
    ("wiiu", "Wii U", ["wiiu"], [".wua", ".wud", ".wux", ".wuhb", ".rpx"], ["wiiu"], False),
    ("switch", "Nintendo Switch", ["switch", "ns"], [".xci", ".nsp", ".nca", ".nro"], ["switch"], False),
    ("psx", "PlayStation", ["psx", "ps1", "playstation"], [".m3u", ".chd", ".pbp", ".cue", ".ecm", ".mds", ".iso", ".img", ".bin"], ["psx"], False),
    ("ps2", "PlayStation 2", ["ps2"], [".chd", ".iso", ".cso", ".ciso", ".gz", ".m3u", ".bin"], ["ps2"], False),
    ("ps3", "PlayStation 3", ["ps3"], [".iso", ".ps3"], ["ps3"], True),
    ("psp", "PlayStation Portable", ["psp"], [".iso", ".cso", ".chd", ".pbp"], ["psp"], False),
    ("n3ds", "Nintendo 3DS", ["n3ds", "3ds"], [".3ds", ".cci", ".cxi", ".3dsx", ".app", ".z3dsx", ".zcci", ".zcxi"], ["n3ds"], False),
    ("nds", "Nintendo DS", ["nds", "ds"], [".nds", ".dsi", ".zip", ".7z"], ["nds"], False),
    ("xbox", "Xbox", ["xbox"], [".iso", ".xiso"], ["xbox"], True),
    ("xbox360", "Xbox 360", ["xbox360", "x360"], [".iso", ".xex", ".zar"], ["xbox360"], True),
    ("dreamcast", "Dreamcast", ["dreamcast", "dc"], [".m3u", ".chd", ".gdi", ".cdi", ".cue"], ["dreamcast"], False),
    ("nes", "Nintendo Entertainment System", ["nes", "famicom", "fc"], [".nes", ".fds", ".unf", ".unif", ".zip", ".7z"], ["nes"], False),
    ("snes", "Super Nintendo Entertainment System", ["snes", "sfc", "superfamicom"], [".sfc", ".smc", ".zip", ".7z"], ["snes"], False),
    ("n64", "Nintendo 64", ["n64"], [".z64", ".n64", ".v64", ".zip", ".7z"], ["n64"], False),
    ("gb", "Game Boy", ["gb", "gameboy"], [".gb", ".zip", ".7z"], ["gb"], False),
    ("gbc", "Game Boy Color", ["gbc"], [".gbc", ".zip", ".7z"], ["gbc"], False),
    ("gba", "Game Boy Advance", ["gba"], [".gba", ".zip", ".7z"], ["gba"], False),
    ("megadrive", "Sega Mega Drive/Genesis", ["megadrive", "genesis", "md"], [".md", ".gen", ".smd", ".bin", ".zip", ".7z"], ["megadrive"], False),
    ("mastersystem", "Sega Master System", ["mastersystem", "sms"], [".sms", ".zip", ".7z"], ["mastersystem"], False),
    ("arcade", "Arcade", ["arcade", "mame", "fbneo", "neogeo"], [".zip", ".7z"], ["arcade"], False),
]

# Commands with these need things the launcher doesn't do (file content injection, starting other apps).
UNSUPPORTED = {"%INJECT%", "%FILEINJECT%", "%ANDROIDAPP%", "%MIMETYPE%"}
TOKEN = re.compile(r"%([A-Za-z0-9_!.\-]+)%(?:=(\S*))?")


def load(name, given):
    if given:
        with open(given, encoding="utf-8") as f:
            return f.read()
    with urllib.request.urlopen(BASE + name, timeout=60) as r:
        return r.read().decode("utf-8")


def parse_command(text):
    """ES-DE command → {emulator, action, category, data, extras, flags}; None if unsupported."""
    if any(u in text for u in UNSUPPORTED):
        return None
    cmd = {"emulator": None, "action": None, "category": None, "data": None, "extras": [], "flags": []}
    for m in TOKEN.finditer(text):
        key, value = m.group(1), m.group(2)
        if key.startswith("EMULATOR_"):
            cmd["emulator"] = key[len("EMULATOR_"):]
        elif key == "ACTION":
            cmd["action"] = value
        elif key == "CATEGORY":
            cmd["category"] = value
        elif key == "DATA":
            cmd["data"] = value
        elif key in ("ACTIVITY_CLEAR_TASK", "ACTIVITY_CLEAR_TOP", "ACTIVITY_NO_HISTORY"):
            cmd["flags"].append(key[len("ACTIVITY_"):])
        else:
            for prefix, kind in (("EXTRABOOL_", "bool"), ("EXTRAINT_", "int"), ("EXTRALONG_", "long"), ("EXTRAARRAY_", "array"), ("EXTRA_", "string")):
                if key.startswith(prefix):
                    if value is None:
                        return None
                    cmd["extras"].append({"type": kind, "name": key[len(prefix):], "value": value})
                    break
    return cmd if cmd["emulator"] else None


def main():
    args = sys.argv[1:]
    systems = ET.fromstring(load("es_systems.xml", args[0] if args else None))
    rules = ET.fromstring(load("es_find_rules.xml", args[1] if len(args) > 1 else None))

    emulators = {}
    for emu in rules.findall("emulator"):
        entries = [e.text.strip() for r in emu.findall("rule") if r.get("type") == "androidpackage" for e in r.findall("entry") if e.text]
        if entries:
            emulators[emu.get("name")] = entries

    by_name = {s.findtext("name"): s for s in systems.findall("system")}
    platforms, used = [], set()
    for pid, name, folders, exts, esde, experimental in PLATFORMS:
        commands = []
        for sysname in esde:
            for c in by_name[sysname].findall("command"):
                parsed = parse_command(c.text or "")
                if parsed is None or parsed["emulator"] not in emulators:
                    continue
                label = c.get("label") or parsed["emulator"]
                # RetroArch cores and standalone emulators often share a name ("Dolphin").
                parsed["label"] = re.sub(r"\s*\(Standalone\)$", "", label) if "(Standalone)" in label else (
                    f"{label} (RetroArch)" if parsed["emulator"] == "RETROARCH" else label)
                parsed["experimental"] = experimental
                commands.append({k: v for k, v in parsed.items() if v not in (None, [], False)})
                used.add(parsed["emulator"])
        platforms.append({"id": pid, "name": name, "folders": folders, "extensions": exts, "experimental": experimental, "commands": commands})

    catalog = {
        "notice": "Emulator start commands and packages from ES-DE (https://es-de.org), "
                  "Copyright (c) Leon Styhre, MIT License.",
        "platforms": platforms,
        "emulators": {k: v for k, v in sorted(emulators.items()) if k in used},
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(catalog, f, ensure_ascii=False, separators=(",", ":"))
    print(f"{OUT}: {len(platforms)} platforms, {sum(len(p['commands']) for p in platforms)} commands, {len(catalog['emulators'])} emulators")


if __name__ == "__main__":
    main()
