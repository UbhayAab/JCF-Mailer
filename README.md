# JCF Mailer - Campaign Studio

**Running locally?** See [docs/LOCAL-DEV.md](docs/LOCAL-DEV.md). No production credential is needed, or should be requested.

The email platform for **Jarurat Care Foundation**. One Spring Boot application that
covers both halves of the foundation's outbound mail:

- **Marketing** - subscriber lists, segments, CSV import, templates, campaigns,
  scheduled sends, multi-step journeys, open and click analytics, suppression and
  unsubscribe handling.
- **Transactional** - a keyed HTTP API that other Jarurat projects call to send OTPs
  and one-off template mail, without any of them needing SES or SMTP credentials of
  their own.

It also embeds a webmail client and a mailbox administration console for the Stalwart
mail server that hosts the foundation's real `@jarurat.care` mailboxes.

Production instance: **https://mailer.jarurat.care**

---

## Which repository is this?

Four repositories carry a version of this name and only one of them is the live source.

| Repository | Visibility | What it is |
|---|---|---|
| **`UbhayAab/JCF-Mailer`** | public | **This one. The source that runs in production. Clone this, branch from `main`, open pull requests here.** |
| `UbhayAab/JCF-Mailer-v2` | public | A single snapshot pushed on 27 Aug 2026 when the v2 rewrite was first published. History only. Nothing lands here. |
| `jarurat-care-private/JCF-Mailer` | public | Frozen at commit `9b7b4d8`, the v1 application from 4 Aug 2026, from before the rewrite. It has the same name and the same landing page and it is not this codebase. |
| `UbhayAab/jarurat-mail-ui` | private | A static HTML design preview on sample data. No backend, no build, not wired to anything. |

If someone sent you a link to any row but the first, you have the wrong code. The
company that reads "the repository is incomplete" is reading row three.

---

## Contents

- [Which repository is this?](#which-repository-is-this)
- [Prerequisites](#prerequisites)
- [Quick start](#quick-start)
- [Configuration](#configuration)
- [Module map](#module-map)
- [Build](#build)
- [Deploy](#deploy)
- [Further documentation](#further-documentation)

---

## Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| JDK | **21** | Non-negotiable. `pom.xml` sets `java.version=21` and the code uses virtual threads. A JDK 17 build fails at compile. |
| PostgreSQL | 14 or newer | `migrate-v2.sql` uses `gen_random_uuid()`, so `pgcrypto` must be available (built in from 13). |
| Maven | none needed | Use the bundled wrapper, `./mvnw` or `mvnw.cmd`. |
| AWS account with SES | - | Only needed to actually send. The app boots and the console works without it. |

The schema is created by Hibernate (`spring.jpa.hibernate.ddl-auto=update`), so there
is no migration tool to install and no DDL to apply by hand on a fresh database.

---

## Quick start

```bash
git clone https://github.com/UbhayAab/JCF-Mailer.git
cd JCF-Mailer

# 1. database
createdb jarurat_mailer
psql -d jarurat_mailer -c "CREATE ROLE mailer_admin LOGIN PASSWORD 'localdev'"
psql -d jarurat_mailer -c "GRANT ALL ON DATABASE jarurat_mailer TO mailer_admin"

# 2. configuration. Copy the example and fill it in; see Configuration below.
cp .env.example .env        # then edit

# 3. run
export JAVA_HOME=/path/to/jdk-21
export DB_PASSWORD=localdev
export ADMIN_EMAIL=you@example.org
export ADMIN_PASSWORD=pick-something
export SESSION_COOKIE_SECURE=false      # required over plain HTTP
export STALWART_LOG_ENABLED=false       # you are not running Stalwart locally
./mvnw spring-boot:run
```

Then open **http://localhost:8080/login** and sign in as the seed owner
(`ADMIN_EMAIL` / `ADMIN_PASSWORD`).

Two things that will otherwise waste your afternoon:

- **`SESSION_COOKIE_SECURE=false` is mandatory locally.** The default is `true`, and a
  browser silently refuses to return a `Secure` cookie over plain HTTP. The symptom is
  a login that appears to succeed and then bounces you straight back to `/login`.
- **The seed account is created once.** `ADMIN_EMAIL` and `ADMIN_PASSWORD` are read by
  `BootstrapService` only while the user table is empty. Changing them later does
  nothing; change the password in the console instead.

Sending mail locally is optional. Without AWS credentials the console, lists, imports,
templates and the journey builder all work; only the actual send fails.

---

## Configuration

**Every setting is an environment variable. There are no credentials in this repo, and
none may ever be committed to it.**

`src/main/resources/application.properties` contains only `${VAR:default}` references.
[`.env.example`](.env.example) documents every variable, what it does, and what breaks
if it is wrong. Copy it, fill it in, and keep the result out of git.

Only four variables have no usable default:

| Variable | Purpose |
|---|---|
| `DB_PASSWORD` | Postgres password for the application role |
| `ADMIN_EMAIL` | Seed owner account, first boot only |
| `ADMIN_PASSWORD` | Seed owner password, first boot only |
| `OTP_PEPPER` | Keyed-hash pepper for stored OTP codes. Without it the service falls back to a per-process key and every outstanding code dies on restart. |

**AWS credentials are deliberately not application properties.** `SesSender` builds the
SES client from the AWS SDK default provider chain, so production uses an EC2 instance
role and no access key is ever written to disk. For local development, export
`AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` in your shell.

In production these live in `/etc/jcfmailer.env` (root, mode 0600), loaded by the
systemd unit. That file is not in this repo and must not be.

---

## Module map

Everything is under `src/main/java/com/jarurat/mailer/`.

### Core

| Package | What is in it |
|---|---|
| `models` | JPA entities: `Subscriber`, `MailingList`, `ListMember`, `Campaign`, `CampaignRecipient`, `EmailTemplate`, `ClickEvent`, `GlobalSuppression`, `TransactionalLog`, `QueuedMessage`, `MailboxSettings`, `User`, `ApiKey`, `AuditLog` |
| `repositories` | Spring Data repositories for the above |
| `services` | `CampaignService` (build and send a campaign), `SesSender` (the single outbound path to SES), `SubscriberService`, `SuppressionService`, `TransactionalMailService`, `AuditService`, `BootstrapService` (seed owner) |
| `controllers` | The HTTP surface. `PageController` serves the console shell; `AdminApi`, `AudienceApi`, `CampaignApi`, `TemplateApi` and `OverviewApi` back it. `TrackingController` handles open pixels and click redirects. `PublicApiV1` and `TransactionalApi` are the key-authenticated external API. |
| `security` | Session login, roles and permissions (`Role`, `Permission`), `SecurityConfig`, and `ApiKeyAuthFilter` / `ApiKeyHasher` for `/api/v1/**` bearer keys |

### Marketing

| Package | What is in it |
|---|---|
| `campaignsplus` | CSV import (`CsvImportService`, `ImportProfile`, `ImportWriter`, `ImportReport`), audience matching, `SafetyCheckService` (blocks a send when the bounce or complaint rate is over threshold), template library |
| `journey` | The multi-step automation engine. `JourneyEngine` walks `JourneyNode` / `JourneyEdge` graphs, `JourneyParticipant` tracks enrolment, `JourneyBucket` does stable A/B branch allocation |
| `analytics` | Open and click tracking, `OpenClassifier` (separates real human opens from Apple Mail Privacy Protection prefetches), engagement segments, aggregates |
| `merge` | `MergeTags`, the `{{TAG}}` substitution used by every template |

### Transactional and deliverability

| Package | What is in it |
|---|---|
| `otp` | One-time-code service: request, verify, redeem, resend, with per-key, per-IP and per-address rate limits. Only a keyed hash of a code is ever stored. |
| `verification` | Address quality checks before a send: syntax, DNS and MX, disposable-domain and role-account detection, optional SMTP probe |
| `messagelog` | Per-recipient delivery log, including the receiving server's verbatim SMTP reply parsed out of the Stalwart logs |
| `sns` | Amazon SNS webhook for bounce and complaint feedback, with signature verification and a topic-ARN allowlist |

### Mail server integration

| Package | What is in it |
|---|---|
| `mail` | JMAP client for Stalwart: sessions, folders, messages, attachments |
| `webmail` | The mailbox screens: reading, composing, contact suggestions, and `MailHtmlSanitizer` for rendering untrusted mail. Also the outbox, where `OutboxService` holds a message for the undo window or until the time it was scheduled for, `OutboxSpool` keeps its attachments on this server's disk until the moment it goes, and `RecipientField` refuses an address the mail server would reject |
| `directory` | Mailbox and domain administration against the Stalwart admin API, and domain health checks (SPF, DKIM, DMARC) |

### Phones and notifications

| Package | What is in it |
|---|---|
| `push` | Web push end to end: VAPID keys, subscriptions, the encrypted payload (`WebPushCrypto`), per-person notification rules, and the notification that tells the sender a scheduled message failed |
| `device` | Remembered devices. A persistent cookie plus an encrypted mailbox credential, so a phone does not ask for the mailbox password on every visit, and the screen that lists and revokes them |
| `config` | Spring configuration with nowhere better to live |

### Front end

Server-rendered Thymeleaf plus vanilla JS. No node, no build step.

- `src/main/resources/templates/` - `console.html` (the whole studio), `mail.html` (the
  mailbox), `landing.html`, `login.html`, `too-many-requests.html`, and `fragments/`,
  whose `icons.html` is the only source of icons in the application
- `src/main/resources/static/js/` - `console.js`, `journey.js`, `charts.js` and
  `templates.js` for the studio; `mail.js`, `mailbulk.js`, `mailkeys.js` and
  `mailsettings.js` for the mailbox; `notify.js`, `pwa.js`, `session.js` and `update.js`
  across both
- `src/main/resources/static/css/style.css`
- `src/main/resources/static/sw.js` - the service worker. **It precaches the scripts
  above, so any change to one of them must come with a bump to `VERSION` in `sw.js`.**
  Miss that and an installed phone keeps serving the old file, which reads exactly like
  a fix that shipped and did nothing.
- [`docs/UI-SPEC.md`](docs/UI-SPEC.md) is the binding contract for anything visual,
  including the stacking order table. Read it before changing a screen.

---

## Build

```bash
export JAVA_HOME=/path/to/jdk-21
./mvnw -B clean package
```

Produces `target/mailer-0.0.1-SNAPSHOT.jar`, a self-contained Spring Boot fat jar of
about 74 MB. `./mvnw -B clean package` runs the whole suite on the way through and has
to end with 0 failures and 0 errors. A jar noticeably smaller than that is a repackage
that failed, usually because another Java process was holding the file open.

The suite boots the entire Spring context against H2, which is what validates every JPA
mapping, every derived repository method name and every JPQL query before a deploy can
find them. Native SQL is not checked there.

Run the jar directly with `java -jar target/mailer-0.0.1-SNAPSHOT.jar`, with the same
environment variables set.

---

## Deploy

The jar is built on a developer machine and shipped to the server. The server no longer
runs Maven: it has under 1 GB of RAM and needed a swapfile to compile.

```bash
./mvnw -B package -DskipTests
scp -i <key> target/mailer-0.0.1-SNAPSHOT.jar <user>@<host>:/home/ubuntu/mailer-new.jar
ssh -i <key> <user>@<host> "bash /home/ubuntu/deploy.sh"
```

[`deploy.sh`](deploy.sh) runs on the server. It backs up Postgres, generates
`OTP_PEPPER` if it is absent, keeps the previous jar for rollback, restarts the
`jcfmailer` unit and verifies that the app came back up. About 40 seconds.

Production topology, ports, file locations and DNS are documented in
[`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md).

### One-off scripts

| Script | Purpose |
|---|---|
| [`migrate-v2.sql`](migrate-v2.sql) | Lifts v1 data into the v2 schema. v1 stored a separate contact row per campaign; v2 has one global subscriber, reusable lists and a per-send recipient snapshot. Idempotent. Historical: already run in production. |
| [`migrate-zoho.py`](migrate-zoho.py) | Resumable IMAP mailbox copy from Zoho to Stalwart. Standard library only. Reads credentials from `migrate-zoho.creds` beside the script, mode 0600, which is **not in this repo** and must never be. |

---

## Further documentation

| Document | Audience |
|---|---|
| [`docs/LOCAL-DEV.md`](docs/LOCAL-DEV.md) | Anyone running the application for the first time. Needs no production credential |
| [`docs/API.md`](docs/API.md) | Developers integrating another project against the public API |
| [`docs/UI-SPEC.md`](docs/UI-SPEC.md) | Anyone changing a screen. The binding design contract |
| [`docs/MAIL-PLATFORM.md`](docs/MAIL-PLATFORM.md) | Operators and integrators. Architecture, the three systems that share the domain, mailbox administration, DNS |
| [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) | Production topology and runbook |
| [`docs/BUILD-PLAN.md`](docs/BUILD-PLAN.md) | Design history and the reasoning behind the v2 rewrite |
| [`docs/CAMPAIGN-PLAN.md`](docs/CAMPAIGN-PLAN.md) | The marketing side in detail: campaigns, journeys, analytics |

---

## Security notes for contributors

- No credential, key, token, password or real recipient address belongs in this
  repository. It is public.
- Secrets are supplied as environment variables. Add new ones to `.env.example` with a
  placeholder value, never a real one.
- `target/` is gitignored. Do not commit a built jar; it embeds the whole classpath.
- The API key accepted on `/api/v1/**` can send DKIM-signed mail from `jarurat.care` to
  any address on earth. There is no CORS on those routes and there must never be.
  Server side only, always.
