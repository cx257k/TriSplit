package jp.trisplit

/**
 * A saved combination of apps, left to right, as flattened component names. Plain strings keep the
 * encoding testable on the JVM.
 */
data class Preset(val id: String, val name: String, val components: List<String>) {
    val packages: List<String> get() = components.map { it.substringBefore('/') }

    /**
     * What this preset offers next to the app already open in a split screen ([partnerPackage]):
     * the partner's column within the preset (null if the preset does not contain it) and the
     * other apps that are still installed ([installed] packages), left to right. The drawer fills
     * the two free columns from these candidates: two fill them directly, more are narrowed down
     * by the user, one leaves the other column to pick.
     */
    fun splitChoices(partnerPackage: String, installed: Set<String>): SplitChoices {
        val index = packages.indexOf(partnerPackage).takeIf { it >= 0 }
        val candidates = components.filterIndexed { i, flat ->
            i != index && flat.substringBefore('/') != partnerPackage && flat.substringBefore('/') in installed
        }
        return SplitChoices(index, candidates)
    }

    data class SplitChoices(val partnerColumn: Int?, val candidates: List<String>)

    companion object {
        private const val FIELD = '\t'
        private const val RECORD = '\n'

        /** One preset per line: id, name, then the components, separated by tabs. */
        fun encode(presets: List<Preset>): String =
            presets.joinToString(RECORD.toString()) { p ->
                (listOf(p.id, sanitize(p.name)) + p.components).joinToString(FIELD.toString())
            }

        fun decode(text: String?): List<Preset> =
            text.orEmpty().split(RECORD).mapNotNull { line ->
                val fields = line.split(FIELD)
                // A line cut short (e.g. a trailing tab) must not yield a preset with an empty app.
                val components = fields.drop(2).filter { it.isNotBlank() }
                if (fields.size < 3 || components.isEmpty()) null else Preset(fields[0], fields[1], components)
            }

        /** Names are typed by the user; tabs and newlines would break the encoding. */
        fun sanitize(name: String): String =
            name.replace(FIELD, ' ').replace(RECORD, ' ').replace('\r', ' ').trim()
    }
}
