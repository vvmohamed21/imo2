package com.example

object AppConfig {
    /**
     * Production Cloud Server URL (Render.com)
     * Can also be adjusted in the UI or pointed to custom Render/Railway domain.
     */
    const val DEFAULT_CLOUD_SERVER_URL = "https://watchroom-server.onrender.com"

    /**
     * Local Emulator fallback URL
     */
    const val LOCAL_EMULATOR_SERVER_URL = "http://10.0.2.2:3000"
}
