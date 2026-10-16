# libwhospairface.so : notre wrapper JNI + OpenCV réduit, lié en statique.
# Compilé par GitHub Actions (.github/workflows/release.yml), jamais ici.
cmake_minimum_required(VERSION 3.22)
project(whospairface CXX)

find_package(OpenCV REQUIRED COMPONENTS core imgproc dnn objdetect)

add_library(whospairface SHARED face.cpp)
target_compile_features(whospairface PRIVATE cxx_std_17)
target_compile_options(whospairface PRIVATE -Os -ffunction-sections -fdata-sections -fvisibility=hidden)
target_link_libraries(whospairface PRIVATE ${OpenCV_LIBS} log)
# Code inutilisé retiré, symboles retirés, pages de 16 Ko (Android 15+).
target_link_options(whospairface PRIVATE
    -Wl,--gc-sections -Wl,--exclude-libs,ALL -s -Wl,-z,max-page-size=16384)
