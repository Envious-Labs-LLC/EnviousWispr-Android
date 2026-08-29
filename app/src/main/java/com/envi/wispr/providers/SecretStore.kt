package com.envi.wispr.providers

interface SecretStore {
    fun put(provider: Provider, secret: String)
    fun get(provider: Provider): String?
    fun remove(provider: Provider)
}
