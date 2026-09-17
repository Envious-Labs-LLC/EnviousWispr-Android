# Model hosting: the proposal for the founder's yes

Status: PROPOSAL, 2026-09-17. Needs a founder decision because it is cloud spend (`GR-NO-CLOUD-SPEND`).
Owner of the delivery mechanics: `.claude/knowledge/model-delivery.md`; the open item it names is
"production model hosting with versioned download manifests".

## The problem in one paragraph

Every install downloads about 1.15 GB (Parakeet 670 MB, S1-mini 484 MB) from Hugging Face at pinned
revisions in repositories we do not control (`csukuangfj/…`, `superwhisper/…`). Hugging Face can rate-limit,
move, or delete a repository, and its terms do not promise us bandwidth. At 1,000 installs that is 1.15 TB
of egress on somebody else's goodwill; at the 100,000-user north star it is 115 TB. The app already
verifies byte count and SHA-256 before admitting a model, so switching the HOST changes only the URLs in
`models/ModelManifest.kt` (`resolve(...)`), not the safety.

## Options, with real numbers (list prices, September 2026, not verified against a fresh quote)

| Option | Monthly cost at 1,000 installs/month (1.15 TB) | At 10,000/month (11.5 TB) | Notes |
|---|---|---|---|
| **A. Cloudflare R2 behind models.enviouswispr.com** (recommended) | Storage 1.2 GB: about $0.02. Egress: $0. Class B reads: about $0.01. **Under $1.** | **Under $1.** | R2 charges no egress. The Cloudflare account and the `enviouswispr.com` zone already exist (`~/.claude/knowledge/infra/cloudflare.md`, R2 already used for backups). Custom domain gives us a URL we own for life. |
| B. AWS S3 + CloudFront, on the AWS credits | CloudFront egress about $0.085/GB: about $98. | About $980. | Credits are $1,120 total and are the AI-experiment budget; 10,000 installs a month would drain them in one month. |
| C. GitHub Releases | $0 | $0 | 2 GB per-file limit (fine), but bandwidth for large binaries is not guaranteed by the terms and a release asset URL is not ours to move. Acceptable as a mirror, not as the primary. |
| D. Stay on Hugging Face | $0 | $0 | Zero control, zero promise. The status quo. |

## Recommendation

**Option A.** Under a dollar a month at any plausible scale, on an account we already run, with a
hostname we own. Keep the pinned Hugging Face URL in the manifest as the SECOND source so a bucket outage
never blocks a first run.

Approval needed: Cloudflare R2 on the existing account, expected under $1/month, hard cap by usage alert
at $5/month. Say "yes R2" and this becomes a SMALL change.

## What the change is, once approved

1. Create bucket `enviouswispr-models`, upload the five files under `parakeet/<revision>/…` and
   `s1-mini/<revision>/…`, attach the custom domain `models.enviouswispr.com`.
2. `models/ModelManifest.kt`: primary URL on our host, Hugging Face as the fallback; sizes and hashes
   unchanged (the same bytes). `ModelManifestTest` pins both.
3. `docs/play-data-safety-answers.md` and the privacy addendum already say "a model host"; the listing
   says nothing host-specific. Nothing else to change.
4. Verify on the emulator: a clean install downloads from the new host (log line names the URL) and both
   models verify.

Not in scope: a versioned manifest served from the host (so a model bump needs no app update). That is
the second half of the open item and a separate plan; it is not needed for launch, because a model bump
ships with an app update today (`model-delivery.md` RULE: a-model-version-bump-is-a-content-change).
