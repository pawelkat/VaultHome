package com.vaulthome.app.index

object SearchConfig {
    const val VECTOR_DIM = 256
    const val MAX_CHUNK_CHARS = 2048
    const val MAX_INPUT_TOKENS = 1024
    const val TOP_K = 12
    const val MODEL_FILE = "embeddinggemma-2-text-270m.litertlm"
    const val MODEL_URL =
        "https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/main/embeddinggemma-2-text-270m.litertlm"
    const val MIN_MODEL_BYTES = 100L * 1024L * 1024L
    const val VAULT_ROOT = "/sdcard/DriveSyncFiles/Vault"
}
