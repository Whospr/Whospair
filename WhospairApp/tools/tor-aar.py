#!/usr/bin/env python3
"""
tor-android récent, compilable avec notre chaîne (Kotlin 1.9, compileSdk 34, JDK 17).

L'AAR officiel (Guardian Project, Maven Central) ne contient que 6 classes
Java (TorService) + libtor.so. Deux choses bloquent, aucune ne touche Tor :
  - classes compilées en Java 24 (format 68) sans rien utiliser au-delà de
    Java 11 (une lambda, des classes internes) → format remis à 61 (Java 17) ;
  - aar-metadata exige compileSdk 37 → ramené à 34.
Sa dépendance kotlin-stdlib 2.x est inutile (aucune classe Kotlin) : elle
n'est pas reprise, voir app/build.gradle.kts.

libtor.so et tout le reste sont copiés tels quels. L'AAR téléchargé est
vérifié (SHA-256 noté ci-dessous, SHA-1 identique à celui de Maven Central).

Usage : python3 tools/tor-aar.py   → app/libs/tor-android-<version>-j17.aar
"""
import hashlib, io, os, sys, urllib.request, zipfile

VERSION = "0.4.9.13"
SHA256 = "6a1fd13e9961e5398fe73e4dc507a859431ed7998f229b121d43dcc56b311b0a"
URL = ("https://repo1.maven.org/maven2/info/guardianproject/tor-android/"
       f"{VERSION}/tor-android-{VERSION}.aar")
JAVA17 = 61
COMPILE_SDK = 34

root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
out = os.path.join(root, "app", "libs", f"tor-android-{VERSION}-j17.aar")

data = urllib.request.urlopen(URL, timeout=120).read()
if hashlib.sha256(data).hexdigest() != SHA256:
    sys.exit("Empreinte inattendue : AAR refusé.")


def patch_jar(raw):
    src = zipfile.ZipFile(io.BytesIO(raw))
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as dst:
        for item in src.infolist():
            body = src.read(item)
            if item.filename.endswith(".class"):
                assert body[:4] == b"\xca\xfe\xba\xbe", item.filename
                body = body[:6] + JAVA17.to_bytes(2, "big") + body[8:]
            dst.writestr(item, body)
    return buf.getvalue()


src = zipfile.ZipFile(io.BytesIO(data))
os.makedirs(os.path.dirname(out), exist_ok=True)
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as dst:
    for item in src.infolist():
        body = src.read(item)
        if item.filename == "classes.jar":
            body = patch_jar(body)
        elif item.filename.endswith("aar-metadata.properties"):
            lines = [l for l in body.decode().splitlines() if not l.startswith("minCompile")]
            lines.append(f"minCompileSdk={COMPILE_SDK}")
            body = ("\n".join(lines) + "\n").encode()
        # .so : rangés sans compression, comme dans l'original.
        dst.writestr(item, body, compress_type=item.compress_type)
print(out)
