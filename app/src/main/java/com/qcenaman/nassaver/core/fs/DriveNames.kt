package com.qcenaman.nassaver.core.fs

/** Your own names for drives, remembered per mount point and applied on top of the system's names. */
class DriveNames(private val inner: DriveProvider, private val store: Store) : DriveProvider {
    interface Store {
        fun load(): Map<String, String>
        fun save(names: Map<String, String>)
    }

    override fun drives(): List<Drive> {
        val names = store.load()
        val used = HashSet<String>()
        return inner.drives().map { d ->
            val wanted = names[d.root.path] ?: d.name
            var name = wanted
            var n = 2
            while (!used.add(name)) name = "$wanted ${n++}"
            Drive(name, d.root)
        }
    }

    /** Renames the drive now called [current]; a blank [name] restores the system's name. Returns an error or null. */
    @Synchronized
    fun rename(current: String, name: String): String? {
        val all = drives()
        val drive = all.firstOrNull { it.name == current } ?: return "That drive isn't connected."
        val clean = name.trim()
        val names = store.load().toMutableMap()
        if (clean.isEmpty()) {
            names.remove(drive.root.path)
        } else {
            if (clean == "." || clean == ".." || clean.any { it == '/' || it == '\\' || it.code < 0x20 }) return "A name can't contain / or \\."
            if (all.any { it.name == clean && it.root != drive.root }) return "Another drive already has that name."
            names[drive.root.path] = clean
        }
        store.save(names)
        return null
    }
}
