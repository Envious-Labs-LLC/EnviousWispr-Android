# Model hosting: the proposal for the founder's yes

Status: SHIPPING, 2026-09-17. The founder approved option A (Cloudflare R2, `GR-NO-CLOUD-SPEND`) in chat
on 2026-09-17, served from the existing `models.enviouslabs.co` host the macOS and Windows apps already use
rather than a new domain; the five model files are live there and the app change is #169.
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

Approval needed: Cloudflare R2 on the existing account, expected under $1/month. There is NO hard spending
cap on R2: a Cloudflare billing alert at $5/month notifies, it does not stop requests or charges. The
bound is the price sheet, not a switch: even 100,000 installs in one month is about 500,000 object reads
(five files each) at $0.36 per million, under $0.20, plus $0.02 storage and $0 egress. Say "yes R2" and
this becomes a MEDIUM change (it touches model delivery, the heart's model-loading path).

## What the change is, once approved

Changing only the URLs would make both models unavailable (Codex review, 2026-09-17): the delivery code
accepts a source only when `validateModelSource` (`models/ModelDelivery.kt:263`) passes, which today
requires host `huggingface.co` and a path containing `/resolve/`, and the availability check reads the
revision out of a `/resolve/<revision>/` path. Each `ModelFile` carries ONE `sourceUrl` and delivery has
no alternate-source fallback. So the scope is:

1. Create bucket `enviouswispr-models`, upload the five files under `parakeet/resolve/<revision>/…` and
   `s1-mini/resolve/<revision>/…` (keeping the `/resolve/<revision>/` path contract so the availability
   check needs no new parser), attach the custom domain `models.enviouswispr.com`.
2. `validateModelSource`: accept exactly two hosts, `models.enviouswispr.com` and `huggingface.co`, HTTPS,
   path containing `/resolve/`; a test pins the allow-list in both directions.
3. `ModelFile`: a primary `sourceUrl` plus a `fallbackUrl` (proposed); the download worker tries the primary and, on
   a refused or failed response before any byte lands, the fallback, logging which host served the file.
   A partial download resumes only against the host it started on (ranges differ per host).
4. `models/ModelManifest.kt`: primary on our host, Hugging Face as the fallback; sizes and hashes
   unchanged (the same bytes). `ModelManifestTest` pins both URLs per file.
5. `docs/play-data-safety-answers.md` and the privacy addendum already say "a model host"; the listing
   says nothing host-specific. Nothing else to change.
6. Verify on the emulator: a clean install downloads from the new host (the log line names the host) and
   both models verify; then block the new host at the emulator's DNS and prove the fallback serves.

Not in scope: a versioned manifest served from the host (so a model bump needs no app update). That is
the second half of the open item and a separate plan; it is not needed for launch, because a model bump
ships with an app update today (`model-delivery.md` RULE: a-model-version-bump-is-a-content-change).
