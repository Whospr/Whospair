// Reconnaissance du visage du propriétaire pour l'écran masqué (1.3.7).
//
// Deux modèles d'OpenCV, et rien d'autre : YuNet trouve les visages, SFace
// en tire une empreinte de 128 nombres. La comparaison avec l'empreinte du
// propriétaire se fait côté Kotlin (FaceEngine). Aucune image ne sort d'ici,
// aucune n'est écrite : tout vit le temps d'un appel.
#include <jni.h>
#include <algorithm>
#include <vector>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/objdetect/face.hpp>

namespace {
struct Engine {
    cv::Ptr<cv::FaceDetectorYN> detector;
    cv::Ptr<cv::FaceRecognizerSF> recognizer;
};
constexpr int kMaxFaces = 3;
constexpr float kScore = 0.8f;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_whisper_shield_FaceEngine_nativeInit(JNIEnv* env, jobject, jstring det, jstring rec) {
    const char* d = env->GetStringUTFChars(det, nullptr);
    const char* r = env->GetStringUTFChars(rec, nullptr);
    Engine* engine = nullptr;
    try {
        engine = new Engine();
        engine->detector = cv::FaceDetectorYN::create(d, "", cv::Size(320, 320), kScore, 0.3f, 5000);
        engine->recognizer = cv::FaceRecognizerSF::create(r, "");
    } catch (...) {
        delete engine;
        engine = nullptr;
    }
    env->ReleaseStringUTFChars(det, d);
    env->ReleaseStringUTFChars(rec, r);
    return reinterpret_cast<jlong>(engine);
}

// Image NV21 de la caméra frontale, tournée à l'endroit selon [rotation].
// Rend les empreintes des visages vus (au plus 3, du plus grand au plus
// petit), bout à bout : 128 nombres par visage ; vide si aucun ; null si erreur.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_whisper_shield_FaceEngine_nativeFaces(JNIEnv* env, jobject, jlong handle,
                                               jbyteArray nv21, jint width, jint height, jint rotation) {
    auto* engine = reinterpret_cast<Engine*>(handle);
    if (engine == nullptr) return nullptr;
    jbyte* data = env->GetByteArrayElements(nv21, nullptr);
    if (data == nullptr) return nullptr;
    jfloatArray result = nullptr;
    try {
        cv::Mat yuv(height + height / 2, width, CV_8UC1, reinterpret_cast<unsigned char*>(data));
        cv::Mat bgr;
        cv::cvtColor(yuv, bgr, cv::COLOR_YUV2BGR_NV21);
        if (rotation == 90) cv::rotate(bgr, bgr, cv::ROTATE_90_CLOCKWISE);
        else if (rotation == 180) cv::rotate(bgr, bgr, cv::ROTATE_180);
        else if (rotation == 270) cv::rotate(bgr, bgr, cv::ROTATE_90_COUNTERCLOCKWISE);

        engine->detector->setInputSize(bgr.size());
        cv::Mat faces;
        engine->detector->detect(bgr, faces);

        std::vector<int> order;
        for (int i = 0; i < faces.rows; ++i) order.push_back(i);
        std::sort(order.begin(), order.end(), [&](int a, int b) {
            return faces.at<float>(a, 2) * faces.at<float>(a, 3) > faces.at<float>(b, 2) * faces.at<float>(b, 3);
        });
        if (static_cast<int>(order.size()) > kMaxFaces) order.resize(kMaxFaces);

        std::vector<float> out;
        for (int i : order) {
            cv::Mat aligned, feature;
            engine->recognizer->alignCrop(bgr, faces.row(i), aligned);
            engine->recognizer->feature(aligned, feature);
            cv::Mat flat = feature.reshape(1, 1);
            flat.convertTo(flat, CV_32F);
            out.insert(out.end(), flat.ptr<float>(), flat.ptr<float>() + flat.total());
        }
        result = env->NewFloatArray(static_cast<jsize>(out.size()));
        if (result != nullptr && !out.empty()) {
            env->SetFloatArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
        }
    } catch (...) {
        result = nullptr;
    }
    env->ReleaseByteArrayElements(nv21, data, JNI_ABORT);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_whisper_shield_FaceEngine_nativeRelease(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Engine*>(handle);
}
