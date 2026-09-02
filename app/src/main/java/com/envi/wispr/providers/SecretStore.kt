package com.envi.wispr.providers

interface SecretStore {
    fun put(provider: Provider, secret: String)
    fun get(provider: Provider): String?
    fun remove(provider: Provider)

    /**
     * Every provider whose stored secret can be READ BACK, whatever the current selection is (#103).
     *
     * "Can be read back" is the same acceptance [get] applies, deliberately: a name present in storage
     * whose blob no longer decrypts is not a key anyone can use, and reporting it would put a connected
     * row over a credential the polish request will fail on.
     *
     * Returns identities only. The plaintext never leaves the implementation.
     */
    fun storedProviders(): Set<Provider>
}
