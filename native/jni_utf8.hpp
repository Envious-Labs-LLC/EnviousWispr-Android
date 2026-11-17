#pragma once

#include <jni.h>

#include <cstdint>
#include <string>

namespace ew::jni {

// Java strings are UTF-16; JNI's GetStringUTFChars/NewStringUTF use modified UTF-8, which
// encodes characters outside the Basic Multilingual Plane (emoji, some CJK name characters) as
// two 3-byte surrogates instead of one 4-byte sequence. These convert to and from standard
// UTF-8 explicitly; any unpaired surrogate or invalid byte becomes U+FFFD (#531, #532).
inline std::string fromJava(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const jsize length = env->GetStringLength(value);
    if (length == 0) return {};
    std::string out;
    out.reserve(static_cast<size_t>(length) * 3);
    struct CharsGuard {
        JNIEnv* env;
        jstring value;
        const jchar* chars;
        ~CharsGuard() { if (chars != nullptr) env->ReleaseStringChars(value, chars); }
    };
    const CharsGuard guard{env, value, env->GetStringChars(value, nullptr)};
    const jchar* chars = guard.chars;
    if (chars == nullptr) return {};
    for (jsize i = 0; i < length; i++) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < length && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (chars[i + 1] - 0xDC00);
            i++;
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD;
        }
        if (cp < 0x80) {
            out += static_cast<char>(cp);
        } else if (cp < 0x800) {
            out += static_cast<char>(0xC0 | (cp >> 6));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            out += static_cast<char>(0xE0 | (cp >> 12));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else {
            out += static_cast<char>(0xF0 | (cp >> 18));
            out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        }
    }
    return out;
}

inline jstring toJava(JNIEnv* env, const std::string& value) {
    std::u16string out;
    out.reserve(value.size());
    const auto* b = reinterpret_cast<const unsigned char*>(value.data());
    const size_t n = value.size();
    for (size_t i = 0; i < n;) {
        uint32_t cp = 0xFFFD;
        size_t extra = 0;
        if (b[i] < 0x80) { cp = b[i]; }
        else if ((b[i] & 0xE0) == 0xC0) { cp = b[i] & 0x1F; extra = 1; }
        else if ((b[i] & 0xF0) == 0xE0) { cp = b[i] & 0x0F; extra = 2; }
        else if ((b[i] & 0xF8) == 0xF0) { cp = b[i] & 0x07; extra = 3; }
        else { out += u'\uFFFD'; i++; continue; }
        bool valid = i + extra < n;
        for (size_t k = 1; valid && k <= extra; k++) {
            if ((b[i + k] & 0xC0) != 0x80) valid = false;
            else cp = (cp << 6) | (b[i + k] & 0x3F);
        }
        const bool overlong = (extra == 1 && cp < 0x80) || (extra == 2 && cp < 0x800) || (extra == 3 && cp < 0x10000);
        if (!valid || overlong || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
            out += u'\uFFFD';
            i++;
            continue;
        }
        if (cp >= 0x10000) {
            cp -= 0x10000;
            out += static_cast<char16_t>(0xD800 + (cp >> 10));
            out += static_cast<char16_t>(0xDC00 + (cp & 0x3FF));
        } else {
            out += static_cast<char16_t>(cp);
        }
        i += extra + 1;
    }
    return env->NewString(reinterpret_cast<const jchar*>(out.data()), static_cast<jsize>(out.size()));
}

inline std::string toUtf8Hex(const std::string& utf8) {
    static constexpr char digits[] = "0123456789abcdef";
    std::string hex;
    hex.reserve(utf8.size() * 2);
    for (unsigned char c : utf8) {
        hex += digits[c >> 4];
        hex += digits[c & 15];
    }
    return hex;
}

}  // namespace ew::jni
