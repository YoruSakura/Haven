package sh.haven.feature.keys

import sh.haven.core.data.db.entities.SshKey

/**
 * How the SSH-keys list on the Keys tab is ordered (#460).
 *
 * Direction is folded into the enum rather than carried as a separate flag:
 * [MANUAL] has no direction, so a `(field, descending)` pair would have an
 * unrepresentable-but-storable state, and one enum name is also the whole
 * persisted representation.
 *
 * The design question #460 raised — does sorting replace the user-defined
 * order from #238, or sit on top of it? — is answered here by making the
 * manual order the *default sort*, i.e. sorting is a view. The move-up /
 * move-down actions only appear in [MANUAL], because reordering a list
 * whose order is computed cannot show a result.
 */
enum class KeySort {
    /** The user-defined order (#238). The default. */
    MANUAL,
    LABEL_ASC,
    LABEL_DESC,
    NEWEST_FIRST,
    OLDEST_FIRST,
    ;

    /** The same field, other direction — what tapping the active option does. */
    fun flipped(): KeySort = when (this) {
        MANUAL -> MANUAL
        LABEL_ASC -> LABEL_DESC
        LABEL_DESC -> LABEL_ASC
        NEWEST_FIRST -> OLDEST_FIRST
        OLDEST_FIRST -> NEWEST_FIRST
    }

    /**
     * What tapping the menu entry for [field] does: switch to that field, or
     * flip direction if it is already the active one. #460 asked for one
     * control per field that toggles direction, not six separate options.
     * [field] is the field's default (ascending / newest) variant.
     *
     * The descending case needs no arm of its own: switching to [field] from
     * its own descending variant *is* the toggle, since a field has exactly
     * two directions.
     */
    fun select(field: KeySort): KeySort =
        if (this == field) flipped() else field

    companion object {
        /** Parse a persisted name, falling back to [MANUAL] for anything unknown. */
        fun parse(name: String?): KeySort =
            entries.firstOrNull { it.name == name } ?: MANUAL
    }
}

/**
 * Apply [sort] to [keys]. [MANUAL][KeySort.MANUAL] returns the input
 * untouched — it is already in the user's order by the time it gets here.
 *
 * Label comparison is case-insensitive: an ASCII-ordered sort puts "Zeus"
 * before "acme", which reads as broken to anyone naming keys casually.
 */
fun sortKeys(keys: List<SshKey>, sort: KeySort): List<SshKey> = when (sort) {
    KeySort.MANUAL -> keys
    KeySort.LABEL_ASC -> keys.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    KeySort.LABEL_DESC -> keys.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.label })
    KeySort.NEWEST_FIRST -> keys.sortedByDescending { it.createdAt }
    KeySort.OLDEST_FIRST -> keys.sortedBy { it.createdAt }
}

/**
 * Split the stored keys into (user keys, USB-drive VM keys), preserving order.
 * Drive keys are infrastructure — minted per opened drive, pinned by that
 * drive's own profile — so the list groups them under their own collapsed
 * section instead of interleaving them with keys the user created. Matched
 * by the stable mint label; renaming one deliberately promotes it to the
 * user section.
 */
fun partitionDriveKeys(keys: List<SshKey>): Pair<List<SshKey>, List<SshKey>> {
    val (drive, user) = keys.partition { it.label == SshKey.USB_DRIVE_VM_LABEL }
    return user to drive
}
