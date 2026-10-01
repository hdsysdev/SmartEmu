#!/usr/bin/env python3
"""Capture real app screens on an explicitly selected physical Android device.

The debug fixture hosts reuse production composables, supplying deterministic
camera images, check data and NFC reader callbacks. No UI is drawn by this script.
"""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "recordings"
SMART = "com.hddev.smartemu.recording"
PRECHECK = "com.t4connex.precheck.rightcheck.debug.recording"
SMART_ACTIVITY = SMART + "/com.hddev.smartemu.recording.RecordingActivity"
PRECHECK_ACTIVITY = PRECHECK + "/com.t4connex.precheck.recording.RecordingActivity"
parser = argparse.ArgumentParser()
parser.add_argument("--serial", required=True)
parser.add_argument("--phase", choices=["smart", "precheck", "extras", "precheck-acknowledge", "preset"], required=True)
args = parser.parse_args()
ADB = ["adb", "-s", args.serial]
RAW.mkdir(parents=True, exist_ok=True)


def adb(*parts, quiet=True):
    return subprocess.check_output(ADB + list(map(str, parts)), text=True).strip()


def pause(seconds=1.2):
    time.sleep(seconds)


def launch(activity, **extras):
    cmd = ["shell", "am", "start", "-W", "-n", activity]
    for k, v in extras.items():
        cmd += ["--es", k, v]
    result = adb(*cmd)
    if "Error:" in result:
        raise RuntimeError(result)
    pause()


def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/recording.xml")
    content = adb("shell", "cat", "/sdcard/recording.xml")
    return ET.fromstring(content)


def tap(label, contains=False):
    tree = dump()
    nodes = [n for n in tree.iter("node") if any(
        (label in n.get(a, "") if contains else label == n.get(a, ""))
        for a in ("text", "content-desc"))]
    if not nodes:
        raise RuntimeError(f"Visible target not found: {label}")
    node = min(nodes, key=lambda n: len(n.get("text", "")))
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", (x1 + x2) // 2, (y1 + y2) // 2)
    pause()


def swipe(up=True):
    adb("shell", "input", "swipe", 550, 1900 if up else 600, 550, 700 if up else 1900, 550)
    pause()


def screenshot(name):
    with (RAW / name).open("wb") as f:
        subprocess.run(ADB + ["exec-out", "screencap", "-p"], stdout=f, check=True)


def clip(name, seconds, action):
    print(f"Recording {name}: {seconds}s", flush=True)
    remote = f"/sdcard/{name}.mp4"
    proc = subprocess.Popen(ADB + ["shell", "screenrecord", "--bit-rate", "10000000", "--time-limit", str(seconds), remote],
                            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    started = time.monotonic()
    pause(2)
    try:
        action()
        remaining = seconds - (time.monotonic() - started)
        if remaining > 0:
            pause(remaining)
    finally:
        if proc.wait(timeout=60) != 0:
            raise RuntimeError(proc.stderr.read().decode())
        adb("pull", remote, RAW / f"{name}.mp4")
        adb("shell", "rm", remote)
    print(f"Saved {name}", flush=True)


if args.phase == "smart":
    adb("shell", "am", "force-stop", SMART)
    launch(SMART_ACTIVITY)
    clip("smart-home", 8, lambda: None)

    def create():
        tap("Use sample details")
        pause(2)
        tap("Edit details")
        swipe()
        tap("Doe")
        adb("shell", "input", "keyevent", 123)
        adb("shell", "input", "keyevent", 67, 67, 67)
        adb("shell", "input", "text", "Smith")
        pause(2)
        adb("shell", "input", "keyevent", 4)
        pause(2)
        tap("Done")
        launch(SMART_ACTIVITY, command="portrait")
        pause(2)

    clip("smart-create", 40, create)

    def show():
        tap("Use this passport")
        pause(2)
        tap("Show photo page")
        pause(3)
        screenshot("specimen-screen.png")

    clip("smart-show", 18, show)
    clip("smart-page", 8, lambda: None)
    screenshot("specimen-screen.png")
    tap("Close")

    def hold():
        tap("Next: hold phones together")
        pause(3)

    clip("smart-hold", 12, hold)
    clip("smart-read", 23, lambda: launch(SMART_ACTIVITY, command="read"))
    clip("smart-result", 12, lambda: swipe())

elif args.phase == "precheck":
    adb("shell", "am", "force-stop", PRECHECK)
    launch(PRECHECK_ACTIVITY)
    pause(3)
    clip("precheck-capture", 14, lambda: (pause(3), tap("Take Photo"), pause(3)))
    screenshot("precheck-captured.png")
    clip("precheck-details", 14, lambda: (tap("NEXT"), pause(4)))
    screenshot("precheck-details.png")
    clip("precheck-ready", 10, lambda: (tap("NEXT"), pause(2)))
    screenshot("precheck-ready.png")
    clip("precheck-read", 25, lambda: (pause(2), tap("START READING"), pause(15)))
    screenshot("precheck-finished.png")
    clip("precheck-finished", 8, lambda: None)
    clip("precheck-acknowledge", 8, lambda: tap("Ok"))

elif args.phase == "precheck-acknowledge":
    clip("precheck-acknowledge", 8, lambda: tap("Ok"))

elif args.phase == "preset":
    launch(SMART_ACTIVITY)
    tap("Back")
    tap("Try another")
    swipe()
    clip("smart-preset-select", 12, lambda: (tap("Accented name"), pause(3)))
    screenshot("smart-preset-selected.png")

elif args.phase == "extras":
    launch(SMART_ACTIVITY)
    tap("Done")
    clip("smart-presets", 24, lambda: (tap("Try another"), swipe(), pause(3)))
    screenshot("smart-presets.png")
    # The unusual-details preset is shown after completing the initial specimen.
    tap("Back")
    clip("smart-history", 16, lambda: (tap("Past reads"), pause(4)))
    screenshot("smart-history.png")
    tap("Back")
    clip("smart-help", 24, lambda: (tap("Help"), pause(3), tap("Nothing happens when I hold the phones together"), pause(3), swipe()))
    screenshot("smart-help.png")
