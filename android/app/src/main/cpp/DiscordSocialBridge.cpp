#define DISCORDPP_IMPLEMENTATION
#include <discordpp.h>

#include <android/log.h>
#include <jni.h>

#include <ctime>
#include <memory>
#include <mutex>
#include <optional>
#include <string>

namespace {
constexpr char kLogTag[] = "AuralisDiscord";
constexpr uint64_t kApplicationId = 1553114990909591732ULL;

std::recursive_mutex clientMutex;
std::unique_ptr<discordpp::Client> client;
JavaVM* javaVm = nullptr;
jclass bridgeClass = nullptr;
jmethodID authorizationCallback = nullptr;
jmethodID readyCallback = nullptr;
jmethodID tokensCallback = nullptr;

template <typename Callback>
void withJni(Callback callback) {
    if (javaVm == nullptr || bridgeClass == nullptr) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (javaVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    callback(env);
    if (attached) javaVm->DetachCurrentThread();
}

void logError(const std::string& message) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message.c_str());
}

std::string toString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void notifyAuthorization(bool success, const std::string& username, const std::string& message) {
    withJni([&](JNIEnv* env) {
        if (authorizationCallback == nullptr) return;
        jstring user = env->NewStringUTF(username.c_str());
        jstring detail = env->NewStringUTF(message.c_str());
        env->CallStaticVoidMethod(bridgeClass, authorizationCallback, success, user, detail);
        env->DeleteLocalRef(user);
        env->DeleteLocalRef(detail);
    });
}

void notifyReady() {
    withJni([](JNIEnv* env) {
        if (readyCallback != nullptr) env->CallStaticVoidMethod(bridgeClass, readyCallback);
    });
}

void notifyTokens(const std::string& access, const std::string& refresh, int32_t expiresIn) {
    withJni([&](JNIEnv* env) {
        if (tokensCallback == nullptr) return;
        jstring accessString = env->NewStringUTF(access.c_str());
        jstring refreshString = env->NewStringUTF(refresh.c_str());
        env->CallStaticVoidMethod(bridgeClass, tokensCallback, accessString, refreshString, expiresIn);
        env->DeleteLocalRef(accessString);
        env->DeleteLocalRef(refreshString);
    });
}

discordpp::ActivityTypes activityTypeFor(const std::string& value) {
    if (value == "Playing") return discordpp::ActivityTypes::Playing;
    if (value == "Streaming") return discordpp::ActivityTypes::Streaming;
    if (value == "Competing") return discordpp::ActivityTypes::Competing;
    return discordpp::ActivityTypes::Listening;
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_auralis_music_data_network_discord_DiscordSocialClient_nativeInitialize(
    JNIEnv* env, jobject bridge, jlong applicationId
) {
    std::lock_guard<std::recursive_mutex> lock(clientMutex);
    if (client) return JNI_TRUE;
    try {
        jclass localClass = env->GetObjectClass(bridge);
        bridgeClass = static_cast<jclass>(env->NewGlobalRef(localClass));
        env->DeleteLocalRef(localClass);
        authorizationCallback = env->GetStaticMethodID(
            bridgeClass, "onAuthorizationResult", "(ZLjava/lang/String;Ljava/lang/String;)V"
        );
        readyCallback = env->GetStaticMethodID(bridgeClass, "onSdkReady", "()V");
        tokensCallback = env->GetStaticMethodID(
            bridgeClass, "onAuthorizationTokens", "(Ljava/lang/String;Ljava/lang/String;I)V"
        );
        if (authorizationCallback == nullptr || readyCallback == nullptr || tokensCallback == nullptr) return JNI_FALSE;
        client = std::make_unique<discordpp::Client>();
        client->SetApplicationId(applicationId == 0 ? kApplicationId : static_cast<uint64_t>(applicationId));
        client->SetStatusChangedCallback([](discordpp::Client::Status status, discordpp::Client::Error error, int32_t) {
            __android_log_print(
                status == discordpp::Client::Status::Ready ? ANDROID_LOG_INFO : ANDROID_LOG_WARN,
                kLogTag,
                "Discord Social SDK status=%d error=%d",
                static_cast<int>(status),
                static_cast<int>(error)
            );
            if (status == discordpp::Client::Status::Ready) notifyReady();
        });
        client->Connect();
        return JNI_TRUE;
    } catch (const std::exception& error) {
        logError(std::string("Discord SDK initialization failed: ") + error.what());
        client.reset();
        return JNI_FALSE;
    }
}

namespace {
std::optional<std::string> optionalOf(JNIEnv* env, jstring value) {
    std::string text = toString(env, value);
    if (text.empty()) return std::nullopt;
    return text;
}
}

// Every field arrives already resolved from the user's Discord settings; empty means "leave out".
extern "C" JNIEXPORT void JNICALL
Java_com_auralis_music_data_network_discord_DiscordSocialClient_nativeUpdatePresence(
    JNIEnv* env,
    jobject,
    jstring name,
    jstring details,
    jstring state,
    jstring largeImage,
    jstring largeText,
    jstring smallImage,
    jstring smallText,
    jstring activityType,
    jboolean isPlaying,
    jlong positionMs,
    jlong durationMs,
    jboolean showWhenPaused
) {
    std::lock_guard<std::recursive_mutex> lock(clientMutex);
    if (!client) return;
    if (!isPlaying && !showWhenPaused) {
        client->ClearRichPresence();
        return;
    }

    discordpp::Activity activity;
    activity.SetType(activityTypeFor(toString(env, activityType)));
    const std::string activityName = toString(env, name);
    if (!activityName.empty()) activity.SetName(activityName);
    activity.SetDetails(optionalOf(env, details));
    activity.SetState(optionalOf(env, state));

    if (isPlaying && durationMs > 0) {
        const uint64_t now = static_cast<uint64_t>(time(nullptr)) * 1000ULL;
        const uint64_t start = now - static_cast<uint64_t>(positionMs < 0 ? 0 : positionMs);
        discordpp::ActivityTimestamps timestamps;
        timestamps.SetStart(start);
        timestamps.SetEnd(start + static_cast<uint64_t>(durationMs));
        activity.SetTimestamps(std::move(timestamps));
    }

    auto large = optionalOf(env, largeImage);
    auto small = optionalOf(env, smallImage);
    if (large || small) {
        discordpp::ActivityAssets assets;
        if (large) {
            assets.SetLargeImage(large);
            assets.SetLargeText(optionalOf(env, largeText));
        }
        if (small) {
            assets.SetSmallImage(small);
            assets.SetSmallText(optionalOf(env, smallText));
        }
        activity.SetAssets(std::move(assets));
    }

    client->UpdateRichPresence(std::move(activity), [](discordpp::ClientResult result) {
        if (!result.Successful()) logError("Discord rich presence update was rejected: " + result.Error());
    });
}

// 0 Online, 3 Idle, 4 Do Not Disturb, 5 Invisible (discordpp::StatusType).
extern "C" JNIEXPORT void JNICALL
Java_com_auralis_music_data_network_discord_DiscordSocialClient_nativeSetOnlineStatus(
    JNIEnv*, jobject, jint status
) {
    std::lock_guard<std::recursive_mutex> lock(clientMutex);
    if (!client) return;
    client->SetOnlineStatus(static_cast<discordpp::StatusType>(status), [status](discordpp::ClientResult result) {
        if (result.Successful()) {
            __android_log_print(ANDROID_LOG_INFO, kLogTag, "Discord status set to %d", static_cast<int>(status));
        } else {
            logError("Discord status change was rejected: " + result.Error());
        }
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_auralis_music_data_network_discord_DiscordSocialClient_nativeAuthorize(JNIEnv*, jobject) {
    std::lock_guard<std::recursive_mutex> lock(clientMutex);
    if (!client) {
        notifyAuthorization(false, "", "Discord is still starting. Please try again.");
        return;
    }

    auto verifier = client->CreateAuthorizationCodeVerifier();
    discordpp::AuthorizationArgs args;
    args.SetClientId(kApplicationId);
    args.SetScopes(discordpp::Client::GetDefaultPresenceScopes());
    args.SetCodeChallenge(verifier.Challenge());
    client->Authorize(std::move(args), [verifier = std::move(verifier)](
        discordpp::ClientResult result,
        std::string code,
        std::string redirectUri
    ) mutable {
        if (!result.Successful() || code.empty()) {
            notifyAuthorization(false, "", "Discord authorization was cancelled or declined.");
            return;
        }
        std::lock_guard<std::recursive_mutex> callbackLock(clientMutex);
        if (!client) return;
        client->GetToken(kApplicationId, code, verifier.Verifier(), redirectUri, [](
            discordpp::ClientResult tokenResult,
            std::string accessToken,
            std::string refreshToken,
            discordpp::AuthorizationTokenType tokenType,
            int32_t expiresIn,
            std::string
        ) {
            if (!tokenResult.Successful() || accessToken.empty()) {
                notifyAuthorization(false, "", "Discord could not finish authorization.");
                return;
            }
            std::lock_guard<std::recursive_mutex> userLock(clientMutex);
            if (!client) return;
            client->FetchCurrentUser(tokenType, accessToken, [tokenType, accessToken, refreshToken, expiresIn](
                discordpp::ClientResult userResult,
                uint64_t,
                std::string username
            ) {
                if (!userResult.Successful()) {
                    notifyAuthorization(false, "", "Discord could not load the account.");
                    return;
                }
                std::lock_guard<std::recursive_mutex> tokenLock(clientMutex);
                if (!client) return;
                client->UpdateToken(tokenType, accessToken, [username, accessToken, refreshToken, expiresIn](discordpp::ClientResult updateResult) {
                    if (updateResult.Successful()) {
                        notifyTokens(accessToken, refreshToken, expiresIn);
                        std::lock_guard<std::recursive_mutex> connectionLock(clientMutex);
                        if (client) client->Connect();
                    }
                    notifyAuthorization(
                        updateResult.Successful(), username,
                        updateResult.Successful() ? "Connected" : "Discord could not connect the account."
                    );
                });
            });
        });
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_auralis_music_data_network_discord_DiscordSocialClient_nativeResumeAuthorization(
    JNIEnv* env, jobject, jstring accessToken, jstring refreshToken, jlong expiresAtMs
) {
    std::lock_guard<std::recursive_mutex> lock(clientMutex);
    if (!client) return;
    const std::string access = toString(env, accessToken);
    const std::string refresh = toString(env, refreshToken);
    const int64_t nowMs = static_cast<int64_t>(time(nullptr)) * 1000LL;
    if (expiresAtMs - nowMs < 24LL * 60 * 60 * 1000 && !refresh.empty()) {
        client->RefreshToken(kApplicationId, refresh, [](discordpp::ClientResult result,
            std::string newAccess, std::string newRefresh,
            discordpp::AuthorizationTokenType type, int32_t expiresIn, std::string) {
            if (!result.Successful() || newAccess.empty()) {
                logError("Discord authorization refresh failed");
                return;
            }
            std::lock_guard<std::recursive_mutex> callbackLock(clientMutex);
            if (!client) return;
            client->UpdateToken(type, newAccess, [newAccess, newRefresh, expiresIn](discordpp::ClientResult updateResult) {
                if (updateResult.Successful()) {
                    notifyTokens(newAccess, newRefresh, expiresIn);
                    std::lock_guard<std::recursive_mutex> connectionLock(clientMutex);
                    if (client) client->Connect();
                }
            });
        });
    } else if (!access.empty()) {
        client->UpdateToken(discordpp::AuthorizationTokenType::Bearer, access,
            [](discordpp::ClientResult result) {
                if (!result.Successful()) {
                    logError("Discord authorization restore failed");
                    return;
                }
                std::lock_guard<std::recursive_mutex> connectionLock(clientMutex);
                if (client) client->Connect();
            });
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_auralis_music_data_network_discord_DiscordSocialClient_nativeClearPresence(JNIEnv*, jobject) {
    std::lock_guard<std::recursive_mutex> lock(clientMutex);
    if (!client) return;
    client->ClearRichPresence();
}

extern "C" JNIEXPORT void JNICALL
Java_com_auralis_music_data_network_discord_DiscordSocialClient_nativePumpCallbacks(JNIEnv*, jobject) {
    std::lock_guard<std::recursive_mutex> lock(clientMutex);
    discordpp::RunCallbacks();
}

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    javaVm = vm;
    return JNI_VERSION_1_6;
}
