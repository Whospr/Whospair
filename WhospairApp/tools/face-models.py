#!/usr/bin/env python3
"""
Modèles de l'écran masqué « seulement mon visage » (1.3.7), depuis le dépôt
officiel opencv_zoo, empreinte SHA-256 vérifiée (comme tools/tor-aar.py).

  YuNet  (détection des visages)      — MIT, Shiqi Yu
  SFace  (empreinte d'un visage, int8) — Apache 2.0

Rangés dans l'app publique seulement (app/src/public/assets/face/) : le
diffuseur n'en a pas besoin. Non suivis par git : relancer après un clone.
Usage : python3 tools/face-models.py
"""
import hashlib, os, sys, urllib.request

BASE = "https://github.com/opencv/opencv_zoo/raw/main/models/"
MODELS = {
    "face_detection_yunet_2023mar.onnx": (
        "face_detection_yunet/face_detection_yunet_2023mar.onnx",
        "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"),
    "face_recognition_sface_2021dec_int8.onnx": (
        "face_recognition_sface/face_recognition_sface_2021dec_int8.onnx",
        "2b0e941e6f16cc048c20aee0c8e31f569118f65d702914540f7bfdc14048d78a"),
}

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "public", "assets", "face")
os.makedirs(out, exist_ok=True)
for name, (path, sha) in MODELS.items():
    target = os.path.join(out, name)
    if os.path.exists(target) and hashlib.sha256(open(target, "rb").read()).hexdigest() == sha:
        continue
    data = urllib.request.urlopen(BASE + path, timeout=300).read()
    if hashlib.sha256(data).hexdigest() != sha:
        sys.exit("Empreinte inattendue : " + name)
    open(target, "wb").write(data)
    print(target)
