package com.kishan.careeros.data.intelligence

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EngineConfig

internal object GemmaRuntimeConfig {
    // Keep in sync with the pinned litertlm-android dependency in app/build.gradle.kts.
    const val SDK_VERSION = "0.8.0"

    // Google's Gallery entry for gemma-3n-E2B-it-int4.litertlm (3655827456 bytes)
    // specifies 4096 total input + output tokens, not 4096 output tokens.
    // https://github.com/google-ai-edge/gallery/blob/main/model_allowlists/1_0_9.json
    const val MAX_CONTEXT_TOKENS = 4096

    fun create(modelPath: String, maxTokens: Int = MAX_CONTEXT_TOKENS): EngineConfig {
        require(maxTokens in 1..MAX_CONTEXT_TOKENS) {
            "Gemma 3n E2B requires a context capacity between 1 and 4096 total tokens."
        }
        return EngineConfig(modelPath = modelPath, backend = Backend.CPU, maxNumTokens = maxTokens)
        // 0.8.0 does not expose thread count: CpuConfig defaults to 4 native threads.
        // Null vision/audio backends disable those executors; null cacheDir uses the model directory.
    }
}
