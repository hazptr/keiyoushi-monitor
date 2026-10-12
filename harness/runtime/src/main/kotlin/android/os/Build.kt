package android.os

// A common retail phone, so extensions that put device fields in headers send plausible values.
object Build {
    @JvmField val MANUFACTURER: String = "samsung"
    @JvmField val BRAND: String = "samsung"
    @JvmField val MODEL: String = "SM-S911B"
    @JvmField val DEVICE: String = "dm1q"
    @JvmField val PRODUCT: String = "dm1qxxx"
    @JvmField val HARDWARE: String = "qcom"
    @JvmField val FINGERPRINT: String = "samsung/dm1qxxx/dm1q:16/BP2A.250605.031/S911BXXU7DYH1:user/release-keys"
    @JvmField val SUPPORTED_ABIS: Array<String> = arrayOf("arm64-v8a")
    @JvmField val ID: String = "BP2A.250605.031"
    @JvmField val DISPLAY: String = "BP2A.250605.031.S911BXXU7DYH1"
    @JvmField val BOARD: String = "kalama"
    @JvmField val BOOTLOADER: String = "S911BXXU7DYH1"
    @JvmField val HOST: String = "build-host"
    @JvmField val USER: String = "dpi"
    @JvmField val TYPE: String = "user"
    @JvmField val TAGS: String = "release-keys"
    @JvmField val TIME: Long = 1754000000000L
    @JvmField val UNKNOWN: String = "unknown"

    @JvmStatic fun getRadioVersion(): String? = null

    object VERSION {
        @JvmField val SDK_INT: Int = 36
        @JvmField val RELEASE: String = "16"
        @JvmField val INCREMENTAL: String = "S911BXXU7DYH1"
        @JvmField val CODENAME: String = "REL"
        @JvmField val SECURITY_PATCH: String = "2026-09-01"
    }
}
