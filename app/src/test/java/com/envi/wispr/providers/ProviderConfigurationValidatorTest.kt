package com.envi.wispr.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConfigurationValidatorTest {
    @Test fun cloudProviderRequiresKey() {
        assertEquals(
            ValidationResult.Invalid(ValidationReason.API_KEY_REQUIRED),
            ProviderConfigurationValidator.validate(ProviderConfiguration(Provider.OPENAI), " "),
        )
    }

    @Test fun cloudProviderDoesNotRequireEndpoint() {
        assertEquals(ValidationResult.Valid, ProviderConfigurationValidator.validate(
            ProviderConfiguration(Provider.CLAUDE), "key-is-only-test-data",
        ))
    }

    @Test fun apiKeyRejectsHeaderControlCharacters() {
        assertEquals(
            ValidationResult.Invalid(ValidationReason.API_KEY_MUST_NOT_CONTAIN_CONTROL_CHARACTERS),
            ProviderConfigurationValidator.validate(
                ProviderConfiguration(Provider.OPENAI), "valid-key\r\nX-Injected: value",
            ),
        )
    }

    @Test fun selfHostedRequiresHttps() {
        assertEquals(
            ValidationResult.Invalid(ValidationReason.ENDPOINT_MUST_BE_LOOPBACK_OR_HTTPS),
            ProviderConfigurationValidator.validate(
                ProviderConfiguration(Provider.SELF_HOSTED_POLISH, "http://example.test/polish"), null,
            ),
        )
        assertEquals(
            ValidationResult.Valid,
            ProviderConfigurationValidator.validate(
                ProviderConfiguration(Provider.SELF_HOSTED_POLISH, "https://example.test/polish"), null,
            ),
        )
    }

    @Test fun httpIsOnlyAllowedForLoopbackDeveloperEndpoint() {
        assertEquals(
            ValidationResult.Valid,
            ProviderConfigurationValidator.validate(
                ProviderConfiguration(Provider.SELF_HOSTED_POLISH, "http://localhost:8080/polish"), null,
            ),
        )
        assertTrue(ProviderConfigurationValidator.validate(
            ProviderConfiguration(Provider.SELF_HOSTED_POLISH, "http://localhost.evil.test/polish"), null,
        ) is ValidationResult.Invalid)
        assertEquals(
            ValidationResult.Valid,
            ProviderConfigurationValidator.validate(
                ProviderConfiguration(Provider.SELF_HOSTED_POLISH, "http://[::1]:8080/polish"), null,
            ),
        )
        assertTrue(ProviderConfigurationValidator.validate(
            ProviderConfiguration(Provider.SELF_HOSTED_POLISH, "http://[::1]:not-a-port/polish"), null,
        ) is ValidationResult.Invalid)
        assertTrue(ProviderConfigurationValidator.validate(
            ProviderConfiguration(Provider.SELF_HOSTED_POLISH, "http://[::1%25wlan0]:8080/polish"), null,
        ) is ValidationResult.Invalid)
        assertTrue(ProviderConfigurationValidator.validate(
            ProviderConfiguration(Provider.SELF_HOSTED_POLISH, "https://example.test:65536/polish"), null,
        ) is ValidationResult.Invalid)
    }

    @Test fun unicodeDnsNamesAreNormalizedForHttpsOnly() {
        assertEquals(
            ValidationResult.Valid,
            ProviderConfigurationValidator.validate(
                ProviderConfiguration(Provider.SELF_HOSTED_POLISH, "https://例え.テスト/polish"), null,
            ),
        )
        assertEquals(
            ValidationResult.Invalid(ValidationReason.ENDPOINT_MUST_BE_LOOPBACK_OR_HTTPS),
            ProviderConfigurationValidator.validate(
                ProviderConfiguration(Provider.SELF_HOSTED_POLISH, "http://例え.テスト/polish"), null,
            ),
        )
    }

    @Test fun endpointWhitespaceIsRejectedInsteadOfValidatedAfterTrim() {
        assertEquals(
            ValidationResult.Invalid(ValidationReason.ENDPOINT_MUST_NOT_CONTAIN_WHITESPACE),
            ProviderConfigurationValidator.validate(
                ProviderConfiguration(Provider.SELF_HOSTED_POLISH, " https://example.test/polish"), null,
            ),
        )
        assertEquals(
            ValidationResult.Invalid(ValidationReason.ENDPOINT_MUST_NOT_CONTAIN_WHITESPACE),
            ProviderConfigurationValidator.validate(
                ProviderConfiguration(Provider.SELF_HOSTED_POLISH, "https://example.test/polish\n"), null,
            ),
        )
    }

    @Test fun endpointCannotContainCredentialsOrFragment() {
        assertEquals(
            ValidationResult.Invalid(ValidationReason.ENDPOINT_MUST_NOT_INCLUDE_CREDENTIALS),
            ProviderConfigurationValidator.validate(
                ProviderConfiguration(Provider.SELF_HOSTED_POLISH, "https://user:pass@example.test/api"), null,
            ),
        )
        assertEquals(
            ValidationResult.Invalid(ValidationReason.ENDPOINT_MUST_NOT_INCLUDE_FRAGMENT),
            ProviderConfigurationValidator.validate(
                ProviderConfiguration(Provider.SELF_HOSTED_POLISH, "https://example.test/api#key"), null,
            ),
        )
    }

    @Test fun disclosureStatesCloudAndSelfHostedNetworkBehavior() {
        assertTrue(Provider.OPENAI.disclosure().networkRequired)
        assertTrue(Provider.GEMINI.disclosure().summary.contains("Google Gemini"))
        assertTrue(Provider.SELF_HOSTED_POLISH.disclosure().summary.contains("self-hosted"))
    }
}
