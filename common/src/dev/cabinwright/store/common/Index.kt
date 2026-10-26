package dev.cabinwright.store.common

/** A parsed index.json (format 1, see the index repo's DESIGN.md). Only build it from verified bytes. */
class Index(m: Map<String, Any?>) {
    val sequence: Long
    val generated: String
    val expires: String
    val keyId: String
    val apps: MutableList<App> = ArrayList()

    class App {
        var id = ""
        var name = ""
        var summary = ""
        var description = ""
        var author = ""
        var homepage = ""
        var category = ""
        var iconUrl = ""
        var iconSha256 = ""

        /**
         * Retirement (empty when not retired): the reason it is no longer offered, the reason
         * it was pulled for safety, and the id of the app that takes its place.
         */
        var deprecated = ""
        var withdrawn = ""
        var replacedBy = ""
        val versions: MutableList<Release> = ArrayList()

        /** A retired app is never installed or updated; cars that have it can only remove it. */
        fun retired(): Boolean = deprecated.isNotEmpty() || withdrawn.isNotEmpty()

        /** Why it's retired, withdrawal first. */
        fun retiredReason(): String = withdrawn.ifEmpty { deprecated }

        /** The newest version that fits this firmware, or null. */
        fun newestFor(firmware: String?): Release? = versions.firstOrNull { it.fits(firmware) }

        fun version(version: String): Release? = versions.firstOrNull { it.version == version }
    }

    /** One version of an app, as the index lists it. */
    class Release {
        var version = ""
        var url = ""
        var sha256 = ""
        var notes = ""
        var size = 0L
        val firmware: MutableList<String> = ArrayList()
        val requires: MutableList<String> = ArrayList()
        val review = Review()

        /** True if this version may be installed on a car with this firmware (null = unknown). */
        fun fits(carFirmware: String?): Boolean =
            firmware.any { it == "*" || (carFirmware != null && it == carFirmware) }
    }

    class Review {
        val types: MutableList<String> = ArrayList()

        /** Package and version code of each APK. */
        val apps: MutableList<Pair<String, String>> = ArrayList()

        /** Name and uid of each service. */
        val services: MutableList<Pair<String, String>> = ArrayList()
        val rootServices: MutableList<String> = ArrayList()
        var systemWrites = false
        var execute = false
    }

    init {
        require(Json.num(m, "format", -1) == 1L) { "unknown index format" }
        sequence = Json.num(m, "sequence", -1)
        require(sequence >= 0) { "index has no sequence" }
        generated = Json.str(m, "generated")
        expires = Json.str(m, "expires")
        keyId = Json.str(m, "key_id")
        for (o in Json.list(m, "apps")) {
            val app = Json.asObject(o)?.let { app(it) } ?: continue
            apps.add(app)
        }
    }

    fun app(id: String): App? = apps.firstOrNull { it.id == id }

    private companion object {
        /** One entry of "apps"; null if it has no id. */
        fun app(a: Map<String, Any?>): App? {
            val app = App()
            app.id = Json.str(a, "id")
            app.name = Json.str(a, "name")
            app.summary = Json.str(a, "summary")
            app.description = Json.str(a, "description")
            app.author = Json.str(a, "author")
            app.homepage = Json.str(a, "homepage")
            app.category = Json.str(a, "category")
            val icon = Json.obj(a, "icon")
            app.iconUrl = Json.str(icon, "url")
            app.iconSha256 = Json.str(icon, "sha256").lowercase()
            app.deprecated = Json.str(a, "deprecated").trim()
            app.withdrawn = Json.str(a, "withdrawn").trim()
            app.replacedBy = Json.str(a, "replaced_by").trim()
            // fail closed: a retirement field that isn't a plain string still retires the app
            if (app.deprecated.isEmpty() && a.containsKey("deprecated")) app.deprecated = "No longer offered."
            if (app.withdrawn.isEmpty() && a.containsKey("withdrawn")) app.withdrawn = "Withdrawn from the store."
            if (app.id.isEmpty()) return null
            if (app.name.isEmpty()) app.name = app.id
            for (vo in Json.list(a, "versions")) {
                val v = Json.asObject(vo)?.let { version(it) } ?: continue
                app.versions.add(v)
            }
            return app
        }

        /** One entry of "versions"; null if it has no version. */
        fun version(vm: Map<String, Any?>): Release? {
            val v = Release()
            v.version = Json.str(vm, "version")
            v.url = Json.str(vm, "url")
            v.sha256 = Json.str(vm, "sha256").lowercase()
            v.size = Json.num(vm, "size", -1)
            v.notes = Json.str(vm, "notes")
            v.firmware.addAll(Json.strings(vm, "firmware"))
            v.requires.addAll(Json.strings(vm, "requires"))
            val r = Json.obj(vm, "review")
            v.review.types.addAll(Json.strings(r, "types"))
            for (x in Json.list(r, "apps")) {
                val xm = Json.asObject(x) ?: continue
                v.review.apps.add(Json.str(xm, "package") to Json.num(xm, "version_code", 0).toString())
            }
            for (x in Json.list(r, "services")) {
                val xm = Json.asObject(x) ?: continue
                v.review.services.add(Json.str(xm, "name") to Json.str(xm, "uid"))
            }
            v.review.rootServices.addAll(Json.strings(r, "root_services"))
            v.review.systemWrites = Json.bool(r, "system_writes")
            // fail closed: anything that says execute, in the review or the types, is refused
            v.review.execute = Json.bool(r, "execute") || v.review.types.contains("execute")
            return if (v.version.isEmpty()) null else v
        }
    }
}
