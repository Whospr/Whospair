#!/usr/bin/env python3
"""sr-Latn.tsv -> sr.tsv (cyrillique). Les noms techniques restent en latin."""
import re
KEEP = re.compile(r"(%\d+\$[sd]|%%|\\n|whospair:…|\b(?:Whospair-a|Whospair-u|Whospair|Android-a|Android-u|Android|PIN-om|PIN-ova|PIN-a|PIN|QR|VPN|IP|AES-256-GCM|X25519|onion|MB|px|Google)\b|\.wup)")
DIG = {"Lj":"Љ","LJ":"Љ","lj":"љ","Nj":"Њ","NJ":"Њ","nj":"њ","Dž":"Џ","DŽ":"Џ","dž":"џ"}
ONE = dict(zip("ABVGDĐEŽZIJKLMNOPRSTĆUFHCČŠabvgdđežzijklmnoprstćufhcčš",
               "АБВГДЂЕЖЗИЈКЛМНОПРСТЋУФХЦЧШабвгдђежзијклмнопрстћуфхцчш"))
def conv(t):
    out=[]; i=0
    while i < len(t):
        if t[i:i+2] in DIG: out.append(DIG[t[i:i+2]]); i+=2
        else: out.append(ONE.get(t[i], t[i])); i+=1
    return "".join(out)
def line(v):
    parts = KEEP.split(v)
    return "".join(p if KEEP.fullmatch(p) else conv(p) for p in parts)
with open("sr-Latn.tsv") as f, open("sr.tsv","w") as o:
    for l in f:
        k,_,v = l.rstrip("\n").partition("\t")
        o.write(k+"\t"+line(v)+"\n")
