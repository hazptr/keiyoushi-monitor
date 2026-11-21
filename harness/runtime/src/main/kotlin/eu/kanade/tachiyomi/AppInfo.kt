package eu.kanade.tachiyomi

object AppInfo {
    fun getVersionCode(): Int = 1
    fun getVersionName(): String = "ext-run"

    // App-wide hidden genres (proposed API), comma separated: EXT_RUN_HIDDEN_GENRES=boys_love,gore
    fun getHiddenGenres(): Set<String> = commaSet("EXT_RUN_HIDDEN_GENRES")
    fun getHiddenGenreNames(): Set<String> = commaSet("EXT_RUN_HIDDEN_GENRE_NAMES")

    private fun commaSet(env: String) = System.getenv(env).orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).toSet()
}
