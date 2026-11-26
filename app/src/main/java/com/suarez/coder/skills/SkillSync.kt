package com.suarez.coder.skills

/**
 * SkillsSync: ajanın commit ettiği `.coder/skills/<ad>/SKILL.md` dosyalarını yeni bir istek beklemeden yakalar.
 * Saf yardımcı: yalnızca yol listesine bakar (ağ/disk yok), böylece test edilebilir. Kancayı kuran taraf
 * (Agent.afterCommit) önbelleği bayat işaretler ve tek satırlık Notice ekler.
 */
object SkillSync {

    /** Bir yol, yetenek klasörüne mi ait? (SKILL.md ve yanındaki alt dosyalar) */
    private val IN_SKILL = Regex("^\\.coder/skills/([^/]+)/.+", RegexOption.IGNORE_CASE)

    /** Commit edilen yollardan etkilenen yetenek adları (normalize, tekilleştirilmiş). */
    fun names(paths: List<String>): Set<String> = paths
        .mapNotNull { p -> IN_SKILL.find(p.trim().removePrefix("./"))?.groupValues?.get(1) }
        .map { SkillParser.normalizeName(it) }
        .filter { SkillParser.isValidName(it) }
        .toSortedSet()

    fun touches(paths: List<String>): Boolean = names(paths).isNotEmpty()

    /** Sohbet zaman çizelgesine düşen tek satır; yetenek yoksa null (gürültü yapmaz). */
    fun notice(paths: List<String>): String? {
        val n = names(paths)
        if (n.isEmpty()) return null
        return "Yetenek güncellendi: ${n.joinToString()}. Katalog sonraki istekte tazelenir; bu turda kullanmak için " +
            "load_skill yerine .coder/skills/${n.first()}/SKILL.md dosyasını github_read_file ile oku."
    }
}
