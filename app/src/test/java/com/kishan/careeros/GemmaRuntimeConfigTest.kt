package com.kishan.careeros

import com.google.ai.edge.litertlm.Backend
import com.kishan.careeros.data.intelligence.GemmaRuntimeConfig
import org.junit.Assert.*
import org.junit.Test

class GemmaRuntimeConfigTest {
    private val path = "/data/user/0/com.kishan.careeros/no_backup/career_models/gemma.litertlm"

    @Test fun configuredArtifactUsesDocumentedContextAndTextOnlyCpuApi() {
        // EngineConfig is a JVM value object; this test never loads JNI or creates an Engine.
        val config = GemmaRuntimeConfig.create(path)
        assertEquals(path, config.modelPath)
        assertEquals(4096, config.maxNumTokens)
        assertEquals(Backend.CPU, config.backend)
        assertNull(config.visionBackend)
        assertNull(config.audioBackend)
        assertNull(config.cacheDir)
    }

    @Test fun former8192OverrideAndOtherInvalidCapacitiesAreRejectedBeforeNativeEntry() {
        listOf(8192, 4097, Int.MAX_VALUE, 0, -1).forEach { tokens ->
            assertThrows(IllegalArgumentException::class.java) { GemmaRuntimeConfig.create(path, tokens) }
        }
    }

    @Test fun validExplicitCapacityIsPassedWithoutRoundingOrDefaultSubstitution() {
        listOf(1, 1024, 4096).forEach { tokens ->
            assertEquals(tokens, GemmaRuntimeConfig.create(path, tokens).maxNumTokens)
        }
    }
}
