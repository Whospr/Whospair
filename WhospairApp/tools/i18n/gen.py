#!/usr/bin/env python3
"""Génère res/values-<lang>/strings*.xml depuis <lang>.tsv (clé<TAB>texte).

Le texte est brut : apostrophes et guillemets tels quels, « \\n » littéral pour
un saut de ligne. Vérifie que chaque clé anglaise est traduite et que les
arguments (%1$s, %d, %%) sont les mêmes qu'en anglais.
"""
import json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "..", "app", "src", "main", "res")
en = json.load(open(os.path.join(HERE, "en.json")))
ARGS = re.compile(r"%(\d+\$)?[sd]|%%")


def esc(t):
    t = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    t = t.replace("'", "\\'").replace('"', '\\"')
    if t.startswith("@") or t.startswith("?"):
        t = "\\" + t
    return t


def main(lang, qualifier):
    tr = {}
    for line in open(os.path.join(HERE, lang + ".tsv"), encoding="utf-8"):
        line = line.rstrip("\n")
        if not line.strip() or line.startswith("#"):
            continue
        k, _, v = line.partition("\t")
        tr[k.strip()] = v.strip()
    missing, bad = [], []
    outdir = os.path.join(RES, "values-" + qualifier)
    os.makedirs(outdir, exist_ok=True)
    for fname, strings in en.items():
        lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
        for k, v in strings.items():
            if k not in tr:
                missing.append(k)
                continue
            if sorted(ARGS.findall(v)) != sorted(ARGS.findall(tr[k])):
                bad.append(k)
            lines.append('    <string name="%s">%s</string>' % (k, esc(tr[k])))
        lines.append("</resources>")
        open(os.path.join(outdir, fname), "w", encoding="utf-8").write("\n".join(lines) + "\n")
    extra = set(tr) - {k for s in en.values() for k in s}
    print(lang, "ok" if not (missing or bad or extra) else "")
    if missing: print("  manquantes:", missing)
    if bad: print("  arguments différents:", bad)
    if extra: print("  inconnues:", sorted(extra))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
