#!/usr/bin/env python3
"""Extracts every Script/LocalScript/ModuleScript from Roblox XML files (.rbxmx/.rbxlx).

Usage: extract_lua.py OUT_DIR FILE.rbxmx [FILE.rbxmx ...]

Writes OUT_DIR/<Top>/<Child>/.../<Name>.lua (a script with children also gets a folder of the
same name for them) and OUT_DIR/INDEX.tsv (path, class, lines). Reads the XML as a stream, so
big place files are fine.
"""
import os
import sys
import xml.etree.ElementTree as ET

SCRIPTS = {"Script", "LocalScript", "ModuleScript"}


def safe(name):
    return "".join(c if c.isalnum() or c in "-_. " else "_" for c in (name or "unnamed")).strip() or "unnamed"


def main():
    out = sys.argv[1]
    rows = []
    for path in sys.argv[2:]:
        stack = []  # [class, name, source]
        for ev, el in ET.iterparse(path, events=("start", "end")):
            if el.tag == "Item":
                if ev == "start":
                    stack.append([el.get("class"), None, None])
                    continue
                cls, name, src = stack[-1]
                if cls in SCRIPTS and src is not None:
                    parts = [safe(s[1]) for s in stack]
                    rel = os.path.join(*parts) + ".lua"
                    dst = os.path.join(out, rel)
                    os.makedirs(os.path.dirname(dst), exist_ok=True)
                    with open(dst, "w", encoding="utf-8") as f:
                        f.write(src)
                    rows.append((rel, cls, src.count("\n") + 1))
                stack.pop()
                el.clear()
            elif ev == "end" and stack:
                n = el.get("name")
                if el.tag == "string" and n == "Name" and stack[-1][1] is None:
                    stack[-1][1] = el.text or ""
                elif el.tag == "ProtectedString" and n == "Source":
                    stack[-1][2] = el.text or ""
    rows.sort()
    with open(os.path.join(out, "INDEX.tsv"), "w", encoding="utf-8") as f:
        f.write("path\tclass\tlines\n")
        for r in rows:
            f.write("%s\t%s\t%d\n" % r)
    print("%d scripts, %d lines" % (len(rows), sum(r[2] for r in rows)))


if __name__ == "__main__":
    main()
