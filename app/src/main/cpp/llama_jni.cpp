// Thin JNI layer over llama.cpp. All strings cross the boundary as UTF-8 byte[]
// (JNI "modified UTF-8" mangles emoji, and token pieces can split a character).
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <unistd.h>
#include <cstdlib>
#include <exception>
#include "GpuPolicy.h"
#include "ggml-backend.h"
extern "C" bool localllm_opencl_driver_available();
extern "C" const char* localllm_opencl_driver_error();
#include <string>
#include <vector>
#include "llama.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LocalLlm", __VA_ARGS__)

namespace {
llama_model*   g_model = nullptr;
llama_context* g_ctx   = nullptr;
std::vector<llama_token> g_cache;      // tokens currently held in the KV cache
std::atomic<bool> g_cancel{false};
int g_n_ctx = 0;
std::atomic<int> g_pf_done{0}, g_pf_total{0};   // prompt-prefill progress, polled from Kotlin
std::string g_stats;                   // last run: prompt size, cache hit, speeds
std::string g_gen_err;                 // why the last generate() failed, shown in the UI
std::vector<ggml_backend_dev_t> g_devices;
std::string g_backend_info;
int g_offloaded=-1;
std::string g_err;                     // last llama.cpp error lines, shown in the UI

// 512-token GPU batches can create excessive transient memory pressure on mobile Vulkan/Adreno.
// Keep GPU prefill conservative while retaining the larger batch in explicit CPU mode.
int g_n_batch = 512;
int g_n_ubatch = 512;

std::string to_str(JNIEnv* env, jbyteArray a) {
    jsize n = env->GetArrayLength(a);
    std::string s(n, '\0');
    env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte*>(s.data()));
    return s;
}
jbyteArray to_jbytes(JNIEnv* env, const char* p, size_t n) {
    jbyteArray a = env->NewByteArray((jsize) n);
    env->SetByteArrayRegion(a, 0, (jsize) n, reinterpret_cast<const jbyte*>(p));
    return a;
}
void free_all() {
    if (g_ctx)   llama_free(g_ctx);
    if (g_model) llama_model_free(g_model);
    g_ctx = nullptr; g_model = nullptr; g_cache.clear();
    g_devices.clear(); g_backend_info.clear();
}
void log_cb(ggml_log_level level, const char* text, void*) {
    __android_log_print(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, "llama", "%s", text);
    const int count=localllm::offloaded_count(text);
    if(count>=0) g_offloaded=count;
    if (level == GGML_LOG_LEVEL_ERROR) {
        g_err += text;
        if (g_err.size() > 500) g_err.erase(0, g_err.size() - 500);
    }
}
}  // namespace

// Use the public upstream path loader for the app-private imported GGUF.
extern "C" JNIEXPORT jstring JNICALL
Java_dev_localllm_LlamaEngine_nativeLoad(JNIEnv* env,jobject,jstring jpath,jint nCtx,jint nThreads,
                                       jstring jbackend,jint gpuLayers,jstring jcacheDir) {
    try {
        const char* raw=env->GetStringUTFChars(jbackend,nullptr);
        const std::string requested=localllm::backend_key(raw);env->ReleaseStringUTFChars(jbackend,raw);
        raw=env->GetStringUTFChars(jpath,nullptr);
        const std::string path(raw);env->ReleaseStringUTFChars(jpath,raw);
        raw=env->GetStringUTFChars(jcacheDir,nullptr);
        const std::string cache_path(raw);env->ReleaseStringUTFChars(jcacheDir,raw);
        if(!localllm::valid_backend(requested) || !localllm::valid_layers(gpuLayers))
            return env->NewStringUTF("Invalid GPU backend or -ngl setting");
        setenv("GGML_OPENCL_KERNEL_CACHE_DIR",cache_path.c_str(),1);
        static bool inited=false;
        if(!inited) { llama_log_set(log_cb,nullptr);llama_backend_init();inited=true; }
        free_all();g_err.clear();g_offloaded=-1;
        const bool use_gpu=gpuLayers!=0;
        const auto batch = localllm::batch_config(requested, use_gpu);
        g_n_batch = batch.batch;
        g_n_ubatch = batch.ubatch;
        if(use_gpu) {
            if(requested=="opencl" && !localllm_opencl_driver_available()) {
                LOGE("OpenCL driver: %s",localllm_opencl_driver_error());
                return env->NewStringUTF("OpenCL vendor driver libOpenCL.so is inaccessible. Select Vulkan and reload. No CPU fallback was made.");
            }
            ggml_backend_dev_t selected=nullptr;
            for(size_t i=0;i<ggml_backend_reg_count();++i) {
                const auto reg=ggml_backend_reg_get(i);
                if(localllm::backend_key(ggml_backend_reg_name(reg))!=requested) continue;
                for(size_t j=0;j<ggml_backend_reg_dev_count(reg);++j) {
                    const auto dev=ggml_backend_reg_dev_get(reg,j);
                    const auto type=ggml_backend_dev_type(dev);
                    bool eligible=type==GGML_BACKEND_DEVICE_TYPE_GPU;
#if LOCALLLM_HAS_IGPU
                    eligible=eligible || type==GGML_BACKEND_DEVICE_TYPE_IGPU;
#endif
                    if(eligible) { selected=dev;break; }
                }
                if(selected) break;
            }
            if(!selected) {
                const std::string error=requested+" has no usable GPU device; check the driver or select another backend. No CPU fallback was made.";
                return env->NewStringUTF(error.c_str());
            }
            g_devices={selected,nullptr};
            g_backend_info=requested+" | "+ggml_backend_dev_description(selected)+" | requested -ngl "+std::to_string(gpuLayers);
        } else {
            g_devices={nullptr};g_backend_info="CPU (explicit -ngl 0)";
        }
        g_backend_info += " | batch " + std::to_string(g_n_batch) + " / microbatch " + std::to_string(g_n_ubatch);
        llama_model_params mp=llama_model_default_params();
        mp.devices=g_devices.data();mp.n_gpu_layers=gpuLayers;
        mp.split_mode=LLAMA_SPLIT_MODE_NONE;mp.main_gpu=0;
        mp.load_mode=LLAMA_LOAD_MODE_MMAP;
        g_model=llama_model_load_from_file(path.c_str(),mp);
        if(!g_model) {
            mp.load_mode=LLAMA_LOAD_MODE_NONE;g_offloaded=-1;
            g_model=llama_model_load_from_file(path.c_str(),mp); // same GPU and layer request
        }
        if(!g_model) {
            const std::string error=g_err.empty()?"Model load failed. Reduce -ngl/context or use a smaller GGUF. No CPU fallback was made.":g_err;
            free_all();return env->NewStringUTF(error.c_str());
        }
        if(use_gpu && g_offloaded==0) {
            free_all();return env->NewStringUTF("Loader reported zero GPU layers. Refusing to label CPU-only execution as GPU acceleration.");
        }
        if(use_gpu) g_backend_info+=g_offloaded>0?" | offloaded "+std::to_string(g_offloaded)+" layers":" | actual layer count unavailable";
        llama_context_params cp=llama_context_default_params();
        cp.n_ctx=nCtx;cp.n_batch=g_n_batch;cp.n_ubatch=g_n_ubatch;
        cp.n_threads=nThreads;cp.n_threads_batch=nThreads;
        cp.offload_kqv=use_gpu;cp.op_offload=use_gpu;
        cp.flash_attn_type=LLAMA_FLASH_ATTN_TYPE_AUTO;
        cp.type_k=GGML_TYPE_F16;cp.type_v=GGML_TYPE_F16;
        g_ctx=llama_init_from_model(g_model,cp);
        if(!g_ctx) {
            cp.flash_attn_type=LLAMA_FLASH_ATTN_TYPE_DISABLED;
            g_ctx=llama_init_from_model(g_model,cp); // same selected GPU
            if(g_ctx) g_backend_info+=" | flash attention disabled";
        }
        if(!g_ctx) {
            const std::string error=g_err.empty()?"Context creation failed. Reduce context/-ngl. No CPU fallback was made.":g_err;
            free_all();return env->NewStringUTF(error.c_str());
        }
        g_n_ctx=(int)llama_n_ctx(g_ctx);
        return env->NewStringUTF("");
    } catch(const std::exception& e) {
        const std::string error=std::string("Native load failed: ")+e.what();
        free_all();return env->NewStringUTF(error.c_str());
    }
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_localllm_LlamaEngine_nativeBackendInfo(JNIEnv* env,jobject) {
    return to_jbytes(env,g_backend_info.data(),g_backend_info.size());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_localllm_LlamaEngine_nativeApplyTemplate(JNIEnv* env, jobject, jobjectArray roles, jobjectArray contents) {
    if (!g_model) return to_jbytes(env, "", 0);
    const jsize n = env->GetArrayLength(roles);
    std::vector<std::string> rs(n), cs(n);
    std::vector<llama_chat_message> msgs(n);
    size_t est = 1024;
    for (jsize i = 0; i < n; i++) {
        auto jr = (jstring) env->GetObjectArrayElement(roles, i);
        const char* r = env->GetStringUTFChars(jr, nullptr);
        rs[i] = r;
        env->ReleaseStringUTFChars(jr, r);
        env->DeleteLocalRef(jr);
        auto jc = (jbyteArray) env->GetObjectArrayElement(contents, i);
        cs[i] = to_str(env, jc);
        env->DeleteLocalRef(jc);
        msgs[i] = { rs[i].c_str(), cs[i].c_str() };
        est += cs[i].size() * 2 + 64;
    }
    const char* tmpl = llama_model_chat_template(g_model, nullptr);
    if (!tmpl) tmpl = "chatml";
    std::string out(est, '\0');
    int len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, out.data(), (int) out.size());
    if (len > (int) out.size()) {
        out.resize(len);
        len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, out.data(), (int) out.size());
    }
    if (len < 0) len = 0;                     // template unsupported -> Kotlin shows an error
    return to_jbytes(env, out.data(), (size_t) len);
}

// Returns tokens generated, -1 on error, -2 if the prompt doesn't fit the context.
extern "C" JNIEXPORT jint JNICALL
Java_dev_localllm_LlamaEngine_nativeGenerate(JNIEnv* env, jobject, jbyteArray jprompt, jint maxNew, jfloat temp, jobject sink) {
    if (!g_model || !g_ctx) return -1;
    g_cancel = false; g_gen_err.clear(); g_err.clear();

    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    std::string prompt = to_str(env, jprompt);

    const auto tokenize_start = std::chrono::steady_clock::now();
    // Token count cannot exceed UTF-8 byte count by much; allocate a byte-sized upper bound
    // to avoid tokenizing every prompt twice. Keep a retry for unusual tokenizers that need more.
    std::vector<llama_token> toks(prompt.size() + 8);
    int token_count = llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(),
                                     toks.data(), (int) toks.size(), true, true);
    if (token_count < 0) {
        toks.resize((size_t) -token_count);
        token_count = llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(),
                                     toks.data(), (int) toks.size(), true, true);
    }
    const double tokenize_s = std::chrono::duration<double>(std::chrono::steady_clock::now() - tokenize_start).count();
    if (token_count <= 0) { g_gen_err = "tokenization failed (empty or unsupported prompt)"; return -1; }
    toks.resize((size_t) token_count);
    // chat templates often emit BOS themselves; don't double it
    if (toks.size() > 1 && toks[0] == llama_vocab_bos(vocab) && toks[1] == toks[0]) toks.erase(toks.begin());

    if ((int) toks.size() >= g_n_ctx - 16) {
        g_gen_err = "prompt is " + std::to_string(toks.size()) + " tokens but context is " + std::to_string(g_n_ctx) +
                    " (system prompt / MCP tool list may be too big: raise context or remove servers)";
        return -2;
    }
    maxNew = std::min<int>(maxNew, g_n_ctx - (int) toks.size() - 1);

    // --- KV cache reuse: only decode the part of the prompt we haven't seen ---
    llama_memory_t mem = llama_get_memory(g_ctx);
    size_t common = 0;
    while (common < g_cache.size() && common < toks.size() && g_cache[common] == toks[common]) common++;
    if (common == toks.size()) common--;      // need at least one fresh token to get logits
    if (!llama_memory_seq_rm(mem, 0, (llama_pos) common, -1)) {   // e.g. recurrent models can't trim
        llama_memory_clear(mem, true);
        common = 0;
    }
    g_cache.resize(common);
    g_pf_total = (int) (toks.size() - common); g_pf_done = 0;
    auto t0 = std::chrono::steady_clock::now();

    for (size_t i = common; i < toks.size(); i += g_n_batch) {
        if (g_cancel) return 0;
        int cnt = (int) std::min<size_t>(g_n_batch, toks.size() - i);
        llama_batch b = llama_batch_get_one(toks.data() + i, cnt);
        int rc = llama_decode(g_ctx, b);
        if (rc != 0) {
            g_gen_err = "prompt decode failed (code " + std::to_string(rc) + ") at token " + std::to_string(i) + "/" +
                        std::to_string(toks.size()) + ", ctx " + std::to_string(g_n_ctx) + ". " + g_err;
            llama_memory_clear(mem, true); g_cache.clear();
            return -1;
        }
        g_cache.insert(g_cache.end(), toks.begin() + i, toks.begin() + i + cnt);
        g_pf_done += cnt;
    }
    auto t1 = std::chrono::steady_clock::now();
    double prefill_s = std::chrono::duration<double>(t1 - t0).count();

    // --- sampling ---
    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temp <= 0.f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    jclass cls = env->GetObjectClass(sink);
    jmethodID onToken = env->GetMethodID(cls, "onToken", "([B)Z");

    int produced = 0;
    double sample_s = 0.0, output_s = 0.0, decode_s = 0.0;
    while (produced < maxNew && !g_cancel) {
        auto sample_start = std::chrono::steady_clock::now();
        llama_token t = llama_sampler_sample(smpl, g_ctx, -1);
        sample_s += std::chrono::duration<double>(std::chrono::steady_clock::now() - sample_start).count();
        if (llama_vocab_is_eog(vocab, t)) break;

        auto output_start = std::chrono::steady_clock::now();
        char buf[256];
        int n = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, true);
        if (n < 0) n = 0;
        jbyteArray arr = to_jbytes(env, buf, (size_t) n);
        jboolean cont = env->CallBooleanMethod(sink, onToken, arr);
        env->DeleteLocalRef(arr);
        if (env->ExceptionCheck()) { env->ExceptionClear(); break; }
        output_s += std::chrono::duration<double>(std::chrono::steady_clock::now() - output_start).count();

        llama_batch b = llama_batch_get_one(&t, 1);
        auto decode_start = std::chrono::steady_clock::now();
        int rc = llama_decode(g_ctx, b);
        decode_s += std::chrono::duration<double>(std::chrono::steady_clock::now() - decode_start).count();
        if (rc != 0) {
            g_gen_err = "decode failed while generating (code " + std::to_string(rc) + "). " + g_err;
            if (produced == 0) { llama_sampler_free(smpl); return -1; }
            break;
        }
        g_cache.push_back(t);
        produced++;
        if (!cont) break;
    }
    llama_sampler_free(smpl);
    double gen_s = std::chrono::duration<double>(std::chrono::steady_clock::now() - t1).count();
    char st[420];
    snprintf(st, sizeof st, "prompt %d tok (%d cached, %d new) | tokenize %.2fs | prefill %.1fs @ %.1f tok/s | %d tok @ %.1f tok/s (decode %.1fs @ %.1f tok/s, sample %.1fs, output %.1fs)",
             (int)toks.size(),(int)common,(int)(toks.size()-common),tokenize_s,prefill_s,
             prefill_s>0?(toks.size()-common)/prefill_s:0.0,
             produced,gen_s>0?produced/gen_s:0.0,
             decode_s,decode_s>0?produced/decode_s:0.0,sample_s,output_s);
    g_stats = st;
    return produced;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_localllm_LlamaEngine_nativeCancel(JNIEnv*, jobject) { g_cancel = true; }

extern "C" JNIEXPORT void JNICALL
Java_dev_localllm_LlamaEngine_nativeFree(JNIEnv*, jobject) { free_all(); }

extern "C" JNIEXPORT jstring JNICALL
Java_dev_localllm_LlamaEngine_nativeLastError(JNIEnv* env, jobject) {
    std::string e = g_gen_err.empty() ? std::string("unknown error") : g_gen_err;
    for (auto& c : e) if ((unsigned char) c > 126 || (unsigned char) c < 9) c = '?';   // keep JNI happy
    return env->NewStringUTF(e.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_localllm_LlamaEngine_nativeProgress(JNIEnv*, jobject) {
    return ((jlong) g_pf_done.load() << 32) | (jlong) (unsigned int) g_pf_total.load();
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_localllm_LlamaEngine_nativeStats(JNIEnv* env, jobject) { return env->NewStringUTF(g_stats.c_str()); }
