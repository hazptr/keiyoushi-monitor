package android.content

import java.io.File

// Minimal JVM shim of android.content.Context: only what core/ and typical extensions touch.
abstract class Context {
    abstract fun getSharedPreferences(name: String, mode: Int): SharedPreferences
    abstract fun getCacheDir(): File
    abstract fun getFilesDir(): File
    open fun getExternalCacheDir(): File? = getCacheDir()
    open fun getExternalFilesDir(type: String?): File? = getFilesDir()
    abstract fun getPackageName(): String
    open fun getApplicationContext(): Context = this

    open fun getSystemService(name: String): Any? = when (name) {
        ACTIVITY_SERVICE -> android.app.ActivityManager()
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    open fun <T> getSystemService(serviceClass: Class<T>): T? = when (serviceClass) {
        android.app.ActivityManager::class.java -> android.app.ActivityManager() as T
        else -> null
    }

    open fun getResources(): android.content.res.Resources = android.content.res.Resources.getSystem()

    /** dataDir is the parent of getFilesDir(), as on Android (/data/user/0/<package>). */
    open fun getApplicationInfo(): android.content.pm.ApplicationInfo = android.content.pm.ApplicationInfo().apply {
        packageName = getPackageName()
        dataDir = getFilesDir().parentFile?.path
        targetSdkVersion = android.os.Build.VERSION.SDK_INT
    }

    companion object {
        const val MODE_PRIVATE = 0
        const val ACTIVITY_SERVICE = "activity"
    }
}
