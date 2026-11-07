# Google Play install referrals

Google Play can pass the labels from a tagged store link to the installed app. EnviousWispr reads those labels in the background, then reports a referral snapshot to its existing PostHog project. This does not identify a person or prove that a particular website caused an installation.

## Share a tagged link

Put the campaign query **inside the Play URL's referrer parameter**, encoded once. Ordinary UTM parameters on the outside of the Play URL are insufficient for this collector.

[Reddit example](https://play.google.com/store/apps/details?id=com.envi.wispr&referrer=utm_source%3Dreddit%26utm_medium%3Dsocial%26utm_campaign%3Dandroid_launch%26utm_content%3Dpost_1)

[YouTube example](https://play.google.com/store/apps/details?id=com.envi.wispr&referrer=utm_source%3Dyoutube%26utm_medium%3Dvideo%26utm_campaign%3Dandroid_launch%26utm_content%3Dvideo_1)

[Website example](https://play.google.com/store/apps/details?id=com.envi.wispr&referrer=utm_source%3Denviouswispr.com%26utm_medium%3Dwebsite%26utm_campaign%3Dandroid_launch%26utm_content%3Dandroid_button)

These are examples for future sharing; existing website buttons and published posts are not retagged by the Android app update. Change the content label for each post or placement. Use campaign labels, never names, emails, user identifiers, search phrases or secrets.

Supported fields are utm_source, utm_medium, utm_campaign and utm_content. Labels are 1 to 64 ASCII letters/digits/dots/underscores/hyphens, starting with a letter or digit, and must pass the app's existing sensitive-pattern filter. Case is preserved. Spaces and arbitrary URLs are not accepted. Any duplicate selected key, malformed query or unsafe selected label rejects attribution for that snapshot. Other query fields, including search terms and advertising click IDs, are ignored. Raw referral strings are neither saved nor sent.

For new links, Python's standard URL encoder avoids hand-escaping errors:

```python
from urllib.parse import urlencode

campaign = urlencode({
    "utm_source": "reddit",
    "utm_medium": "social",
    "utm_campaign": "android_launch",
    "utm_content": "post_2",
})
print("https://play.google.com/store/apps/details?" + urlencode({
    "id": "com.envi.wispr",
    "referrer": campaign,
}))
```

## Read the results

The event is install_referrer.received, with the app/environment/version and installation identifier already used by other Android events.

| Field | Meaning |
|---|---|
| referral_status | tagged, unattributed, or invalid |
| referral_source | Publisher-supplied source label, such as reddit |
| referral_medium | Publisher-supplied channel label, such as social |
| referral_campaign | Campaign label |
| referral_content | Individual post, video or placement label |
| referral_click_s | Positive Play server click timestamp, seconds since epoch, when available |
| referral_install_s | Positive Play server install-start timestamp, seconds since epoch, when available |

Missing attribution stays unknown. The app does not invent direct/organic attribution. A returned google-play/organic label is preserved as supplied by Play, not inferred. The install-start timestamp is not a completed download or first-open timestamp.

Existing installations receive one lookup after updating into this feature. Google retains referral information for up to 90 days. That backfill is **not a new installation**. Successful lookups, including unattributed/invalid responses, are remembered; unavailable services retry at a later process start, without polling. An unsupported service is remembered without a snapshot. Updates preserve the marker; uninstall/data clearing can create a new installation identity.

Delivery uses existing best-effort analytics. A process killed around capture/marker persistence can lose or repeat a snapshot. Count distinct installation IDs, never raw event totals. Source labels do not certify external customers.

For customer acquisition reporting, reuse the full-history test exclusions in the private [Android active-installation report](https://us.posthog.com/project/354235/insights/WByxnI5B), including its maintained internal-installation IDs. Filter production Android. Group referral snapshots by source/campaign/content and count unique installation IDs. Use referral_install_s, where available, for install-time cohorts; the event's date measures when the snapshot was collected and includes backfill.

This exploratory query excludes marked test/review installations but **does not include the private internal-ID exclusion list**. Its output is candidate installations, not a customer count. Add the maintained private exclusions from the saved report before presenting customer totals.

```sql
SELECT
    coalesce(properties.referral_source, 'unknown') AS source,
    coalesce(properties.referral_campaign, 'unknown') AS campaign,
    coalesce(properties.referral_content, 'unknown') AS content,
    uniqExact(distinct_id) AS candidate_installations
FROM events
WHERE timestamp >= now() - INTERVAL 30 DAY
    AND event = 'install_referrer.received'
    AND properties.app = 'enviouswispr-android'
    AND properties.environment = 'production'
    AND distinct_id NOT IN (
        SELECT distinct_id
        FROM events
        WHERE timestamp >= toDateTime('2026-09-01 00:00:00', 'America/New_York')
            AND timestamp < now()
            AND properties.app = 'enviouswispr-android'
            AND (
                properties.$is_emulator = true
                OR properties.test_lab = true
                OR (
                    coalesce(properties.device_model, properties.$device_model) = 'OnePlus8Pro'
                    AND properties.os_version = '11'
                )
            )
    )
GROUP BY source, campaign, content
ORDER BY candidate_installations DESC
LIMIT 500
```

## Validation and rollout

The official SDK is pinned to 2.2. Automated parser/privacy and lifecycle tests establish selected-field handling, retry/remember behavior and client cleanup. A sideloaded emulator cannot establish that Play passed campaign tags through a real store installation. Verify that path by following a tagged link, installing a Play-distributed candidate on a separate test installation and opening the app. Read the resulting snapshot and repeat a launch to check that it is not re-collected. Keep test installation IDs excluded from customer reports.

Collection becomes available only in app builds containing this feature. A GitHub merge, internal tester build and approved public release are separate delivery states. There is no Discord destination or advertising identifier collection in this change.

Official references: [client library](https://developer.android.com/google/play/installreferrer/library), [response fields and retention](https://developer.android.com/google/play/installreferrer/igetinstallreferrerservice), [tagged Play link example](https://developer.android.com/games/playgames/user-acquisition).
