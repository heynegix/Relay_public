package com.example.relay.rescue.trust

/** TEST ONLY: no test root, public key, or private key is embedded in this helper. */
class InMemoryRegionalDirectoryPersistence : RegionalDirectoryPersistence {
    private val lock = Any()
    private val values = linkedMapOf<String, StoredRegionalDirectory>()
    var failWrites: Boolean = false
    var transactionCount: Int = 0

    override fun <T> transaction(block: () -> T): T = synchronized(lock) {
        transactionCount += 1
        block()
    }
    override fun find(regionId: String): StoredRegionalDirectory? = values[regionId]
    override fun all(): List<StoredRegionalDirectory> = values.values.toList()
    override fun replace(record: StoredRegionalDirectory): Boolean {
        if (failWrites) return false
        values[record.regionId] = record
        return true
    }
}
