package android.content.pm

/** The fields extensions read (public Java fields on Android, so @JvmField). */
open class ApplicationInfo {
    @JvmField var packageName: String? = null
    @JvmField var dataDir: String? = null
    @JvmField var nativeLibraryDir: String? = null
    @JvmField var sourceDir: String? = null
    @JvmField var targetSdkVersion: Int = 0
    @JvmField var minSdkVersion: Int = 0
    @JvmField var flags: Int = 0
}
