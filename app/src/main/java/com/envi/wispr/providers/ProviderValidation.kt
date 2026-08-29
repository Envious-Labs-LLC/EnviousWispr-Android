package com.envi.wispr.providers

import java.net.URI
import java.net.IDN
import java.net.URISyntaxException
import java.util.Locale

data class ProviderConfiguration(
    val provider: Provider,
    val endpoint: String? = null,
)

sealed interface ValidationResult {
    data object Valid : ValidationResult
    data class Invalid(val reason: ValidationReason) : ValidationResult
}

enum class ValidationReason {
    API_KEY_REQUIRED,
    API_KEY_MUST_NOT_CONTAIN_CONTROL_CHARACTERS,
    ENDPOINT_REQUIRED,
    ENDPOINT_MUST_BE_HTTPS,
    ENDPOINT_MUST_BE_LOOPBACK_OR_HTTPS,
    ENDPOINT_MUST_HAVE_HOST,
    ENDPOINT_MUST_NOT_INCLUDE_CREDENTIALS,
    ENDPOINT_MUST_NOT_INCLUDE_FRAGMENT,
    ENDPOINT_MUST_NOT_CONTAIN_WHITESPACE,
}

object ProviderConfigurationValidator {
    fun validate(configuration: ProviderConfiguration, apiKey: String?): ValidationResult {
        val capabilities = configuration.provider.capabilities()
        if (apiKey?.any(Char::isISOControl) == true) {
            return ValidationResult.Invalid(ValidationReason.API_KEY_MUST_NOT_CONTAIN_CONTROL_CHARACTERS)
        }
        if (capabilities.requiresApiKey && apiKey.isNullOrBlank()) {
            return ValidationResult.Invalid(ValidationReason.API_KEY_REQUIRED)
        }
        if (capabilities.requiresEndpoint) {
            val endpoint = configuration.endpoint
            if (endpoint.isNullOrBlank()) return ValidationResult.Invalid(ValidationReason.ENDPOINT_REQUIRED)
            return validateSelfHostedEndpoint(endpoint)
        }
        return ValidationResult.Valid
    }

    private fun validateSelfHostedEndpoint(endpoint: String): ValidationResult {
        if (endpoint.any(Char::isWhitespace) || endpoint.any(Char::isISOControl)) {
            return ValidationResult.Invalid(ValidationReason.ENDPOINT_MUST_NOT_CONTAIN_WHITESPACE)
        }
        val uri = try {
            URI(endpoint)
        } catch (_: URISyntaxException) {
            return ValidationResult.Invalid(ValidationReason.ENDPOINT_MUST_BE_LOOPBACK_OR_HTTPS)
        } catch (_: IllegalArgumentException) {
            return ValidationResult.Invalid(ValidationReason.ENDPOINT_MUST_BE_LOOPBACK_OR_HTTPS)
        }
        if (uri.userInfo != null) return ValidationResult.Invalid(ValidationReason.ENDPOINT_MUST_NOT_INCLUDE_CREDENTIALS)
        if (uri.fragment != null) return ValidationResult.Invalid(ValidationReason.ENDPOINT_MUST_NOT_INCLUDE_FRAGMENT)
        val scheme = uri.scheme?.lowercase()
        val host = normalizedHost(uri)
            ?: return ValidationResult.Invalid(ValidationReason.ENDPOINT_MUST_HAVE_HOST)
        val loopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
        return when {
            scheme == "https" -> ValidationResult.Valid
            scheme == "http" && loopback -> ValidationResult.Valid
            else -> ValidationResult.Invalid(
                if (loopback) ValidationReason.ENDPOINT_MUST_BE_HTTPS
                else ValidationReason.ENDPOINT_MUST_BE_LOOPBACK_OR_HTTPS,
            )
        }
    }

    /**
     * URI.host is null for valid Unicode host names and includes brackets for IPv6 on Android/JVM.
     * Parse the already URI-validated authority, then canonicalize DNS names with IDN's strict
     * rules. This keeps the HTTP loopback exception exact and avoids accepting userinfo or a
     * malformed host:port shape through a fallback parser.
     */
    private fun normalizedHost(uri: URI): String? {
        val authority = uri.rawAuthority ?: return null
        val host = when {
            authority.startsWith("[") -> {
                val closingBracket = authority.indexOf(']')
                if (closingBracket <= 1) return null
                val suffix = authority.substring(closingBracket + 1)
                if (!suffix.isValidPortSuffix(uri.port)) {
                    return null
                }
                authority.substring(1, closingBracket).also {
                    if (!it.contains(':')) return null
                }
            }
            else -> {
                if (authority.count { it == ':' } > 1) return null
                val colon = authority.indexOf(':')
                if (colon >= 0 && !authority.substring(colon).isValidPortSuffix(uri.port)) return null
                authority.substringBefore(':')
            }
        }
        if (host.isEmpty()) return null
        return try {
            if (host.contains(':')) {
                // URI has already required brackets for IPv6. Keep a zone id in the canonical
                // value so an interface-scoped address cannot become the HTTP loopback exception.
                host.lowercase(Locale.ROOT)
            } else {
                IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES)
                    .lowercase(Locale.ROOT)
                    .removeSuffix(".")
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun String.isValidPortSuffix(port: Int): Boolean {
        if (isEmpty()) return true
        return startsWith(":") && length > 1 && substring(1).all(Char::isDigit) && port in 0..65535
    }
}
