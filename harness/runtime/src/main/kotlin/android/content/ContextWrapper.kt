package android.content

import java.io.File

/** android.content.ContextWrapper: every call goes to the base context (an Application's or Activity's ContextImpl). */
open class ContextWrapper(private var mBase: Context?) : Context() {
    open fun getBaseContext(): Context? = mBase

    protected open fun attachBaseContext(base: Context) {
        check(mBase == null) { "Base context already set" }
        mBase = base
    }

    private val base: Context get() = mBase ?: throw NullPointerException("ContextWrapper has no base context yet")

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences(name, mode)
    override fun getCacheDir(): File = base.getCacheDir()
    override fun getFilesDir(): File = base.getFilesDir()
    override fun getExternalCacheDir(): File? = base.getExternalCacheDir()
    override fun getExternalFilesDir(type: String?): File? = base.getExternalFilesDir(type)
    override fun getPackageName(): String = base.getPackageName()
    override fun getApplicationContext(): Context = base.getApplicationContext()
    override fun getSystemService(name: String): Any? = base.getSystemService(name)
    override fun <T> getSystemService(serviceClass: Class<T>): T? = base.getSystemService(serviceClass)
    override fun getResources(): android.content.res.Resources = base.getResources()
    override fun getApplicationInfo(): android.content.pm.ApplicationInfo = base.getApplicationInfo()
}
