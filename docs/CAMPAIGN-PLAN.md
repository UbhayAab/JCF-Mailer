# Campaign Studio: the plan

One ranked, buildable plan, synthesised from seven investigations into JCF-Mailer.
Instructions to an implementer. Every claim below was re-verified against the working
tree at HEAD unless the line says otherwise.

Written 2026-08-30. The hard date in this document is **14 November 2026**, eleven weeks out.

---

## Corrections to the brief, before anyone acts on it

Four premises in circulation are wrong. Fix your mental model first.

1. **The CSP is not `default-src 'self'`.** `SecurityConfig.java:442-450` ships
   `default-src 'self'; img-src 'self' data: https:; style-src 'self' 'unsafe-inline';
   script-src 'self' 'unsafe-inline'; frame-src 'self' blob: data:; object-src 'none'`.
   `script-src` allows `'unsafe-inline'`, and **166 inline handlers in `console.html`
   depend on it**. Only `unsafe-eval` is absent. Do not "tighten" this.

2. **`UI-SPEC.md` sections 14.9 and 14.11 are stale and dangerous.** 14.9 lists push
   notifications, multi-select batch actions and draft autosave as deliberately not
   built. All three are built and shipped. 14.11 states a CSP that is not the one in
   the code. The spec's own opening rule is "if a rule here and the code disagree, the
   code is wrong", so a diligent newcomer will delete three working features and take
   the console down. **Correct the spec in Phase 1 or the trap fires on the next hire.**

3. **The message log does not have SMTP replies for campaign mail.** `StalwartDeliveryLog`
   reads Stalwart's local log, and campaigns go to the SES API over HTTPS and never touch
   Stalwart. `aws.ses.configurationSet=${SES_CONFIGURATION_SET:}` is **blank**
   (`application.properties:69`), so campaign mail today has **zero** per-recipient
   delivery data. Verbatim SMTP replies exist for webmail and SMTP submission only.

4. **A/B testing is not a gap.** `JourneyService.variantReport` already computes per-arm
   open/click/CTOR, refuses to name a winner under 300 delivered per arm, and reports a
   minimum detectable effect. Mailchimp's own help page publishes no minimum audience
   size and no per-variation floor. Yours is the honest one. The gap is that it only
   exists inside a journey SPLIT node.

---

# 1. FINISH FIRST

Finished work the owner cannot see. All of it is cheaper than any new feature and it has
already been mistaken for missing features three times. Nothing in section 2 starts until
this section is done.

### F1. Set the SES configuration set and the SNS topic. Zero code. Highest single item in this document.

`SnsWebhookController` and `SnsMessageVerifier` are written, signature-verified and
reachable. `SnsWebhookController.handle` switches on `Bounce`, `Complaint`, `Delivery`
(verified: lines 100, 115, 125) and receives nothing, because no configuration set exists
to publish to. `StalwartDeliveryLog`'s own comment records why it was abandoned: the IAM
user is denied `SNS:CreateTopic`. That is an IAM policy, not a physical constraint.

Do, in the AWS console, as an admin principal:

- create an SNS topic, subscribe the app's `/api/sns` endpoint
- create an SES configuration set with event publishing to that topic, selecting at
  minimum Send, Delivery, Bounce, Complaint, Reject, DeliveryDelay
- set `SES_CONFIGURATION_SET` and `JARURAT_SNS_ALLOWEDTOPICARNS` in `/etc/jcfmailer.env`
- restart, send one campaign to one address, confirm rows land

**Cost of leaving it:** `SafetyCheckService` presents a 30-day computed bounce rate as if
it were the number AWS acts on. It is not. AWS computes over an unpublished
"representative volume" and counts only hard bounces, so the two rates will disagree.
Today the safety gate is an estimate dressed as a measurement, and every item in section 2
that touches deliverability is blocked behind this.

Size: **half a day, no code.**

### F2. Fix the DMARC record. Zero code. Do it the same afternoon.

Not built-and-unconnected, but the same class: free, today, and it unblocks a decision.

- `rua` currently points at a gmail.com address. RFC 7489 section 7.1 requires a TXT
  record at `jarurat.care._report._dmarc.gmail.com` for that to be valid, and only Google
  can publish it. Conforming reporters drop the reports. **Change `rua` to
  `mailto:dmarc@jarurat.care`.** The mailbox exists on Stalwart and the app already has
  JMAP read access to it.
- **Delete `ruf` entirely.** Failure reports carry recipient addresses and message
  fragments. For an organisation corresponding with cancer patients, receiving other
  people's message content into a mailbox is a liability, not an asset.
- **Delete `sp=none`.** It instructs every receiver to ignore DMARC failures on every
  subdomain, including ones an attacker invents. There is no legitimate subdomain From:
  stream it protects: `ses.jarurat.care` is a MAIL FROM domain and never a From: domain,
  and `mailer.jarurat.care` is a web host.
- Leave `p=none` for now, and leave `~all` on the root SPF alone. Google, Yahoo and
  Microsoft all explicitly accept `p=none`. Nobody requires enforcement.

**Cost of leaving it:** a dropped report looks exactly like a quiet week, so the DMARC
policy decision is currently being made on a sample of unknown size.

Size: **one hour, DNS only.**

### F3. Reconcile the devices contract. This is broken, not merely missing.

`mail.js:1349-1367` carries the comment "THE CONTRACT THIS CODES AGAINST HAS NOT LANDED
YET". Verified mismatch:

| `mail.js` expects | `DeviceApi` serves (verified) |
|---|---|
| `GET /api/mail/devices` then `/api/devices` | `/api/devices` only |
| `{ enabled, devices: [...] }` | a bare JSON array |
| `POST /revoke` with `id=`, `POST /revoke-all` | `DELETE /{id}`, `DELETE /` |
| `name`, `platform`, `ip`, `mailbox`, `lastSeenAt` | `label`, `lastIp`, `lastSeen` |

`DEV.list = (data && data.devices) || []` on a bare array is `[]`, so the sheet always
says no devices. Revoke 404s and `deviceCall` throws "Signed-in devices are not available
on this server yet."

Pick one side and fix the other. Fix `DeviceApi`'s stale javadoc ("There is no screen for
this yet") either way, because that sentence is why nobody re-checked the shapes.

**Cost of leaving it:** a lost phone holds a mailbox for 180 days. The only recovery is
changing the Stalwart password, which signs out every device of every person sharing that
mailbox. This is the exact liability the screen was built to prevent.

Size: **half a day.**

### F4. Prove one push arrives, and render the diagnostic that already exists.

`GET /api/mail/push/config` returns `newMailRegistered`, `newMailVerified`, `failureCount`,
`lastError` and `recentFailures`. All five are read **zero times** by `notify.js` or
`mailsettings.js`. A VAPID key mismatch fails 403 forever, silently, and looks identical
to nothing happening.

- `curl` that endpoint with a session cookie today. That is the whole first step.
- Confirm Stalwart's `jmap.webPushKey` holds the same PEM as `PUSH_VAPID_PRIVATE_KEY`, and
  `jmap.webPushContact` matches `PUSH_VAPID_SUBJECT`.
- Render those five fields in the settings sheet.
- Test on one real iPhone installed to the Home Screen. The iOS gate in `notify.js:834-961`
  is correct; what is unproven is whether the push arrives.

Stalwart implemented RFC 9749 (VAPID for JMAP Web Push) in v0.16.14; production runs
0.16.19, so the server side is capable. Before 0.16.14, Chromium and Safari could not
receive push at all.

**Cost of leaving it:** roughly 3,500 lines of push code whose working state is unknown
and unknowable.

Size: **half a day.**

### F5. Wire the outbox. Do not delete it.

`OutboxApi` (545) + `OutboxService` (646) + `QueuedMessage` (354) +
`QueuedMessageRepository` (204) = 1,749 production lines plus 705 test lines, with **zero
callers**. Verified: the string `outbox` does not appear in any file under
`src/main/resources/static/js/`. `MailSettingsApi.java:358` hard-codes
`out.put("undoSendHonoured", false)`.

The recommendation is **wire it**, against the general rule of deleting orphans, for one
reason specific to this organisation. The mailbox this serves corresponds with patients'
families about cancer. A reply sent to the wrong thread, or a draft sent before it was
finished, is not an embarrassment here. Undo-send is the only control in the product that
makes that recoverable.

Work: the compose sheet posts to `/api/mail/outbox/send` instead of `/api/mail/send` when
`undoSendSeconds > 0`, plus an undo toast, plus the outbox list view for scheduled sends.
Remove the hard-coded `false`.

Secondary payoff: `PushService.notify` has 9 test references and **0 production callers**,
and its only producer is `OutboxFailurePush.sweep()`, which polls `queued_message` for
failed rows that can never exist. Wiring the outbox is what gives the app a self-raised
notification lane at all.

If the owner decides against undo-send, **delete all 2,454 lines in one hour**. Do not
leave it a third way.

Size: **one day to wire, one hour to delete.**

### F6. Make the settings sheet honest. Seven controls save to Postgres and do nothing.

`mail.js` touches `window.MailSettings` in exactly two places and never calls
`MailSettings.get()`.

- **Works:** signature, signature-on-new, signature-on-reply, vacation (delegated to
  Stalwart's JMAP `VacationResponse`), notification rules.
- **Saved and ignored, and the UI admits it:** `undoSendSeconds`, `requestReadReceipt`.
- **Saved and ignored silently:** `preferHtml`, `messagesPerPage` (`S.limit` is hard-coded
  `40` at `mail.js:116`), `readingPane`, `loadRemoteImages`, `defaultReply`.

`defaultReply` is the one that gets noticed. Set "Reply button sends to everyone", press
Reply, it replies to the sender only, every time, all day.

Either read `MailSettings.get()` for the five silent ones, or give them the same "stored
but not yet applied" notice the undo and receipt controls already carry. Do not ship a
mail client whose Settings screen lies, because Settings is the first thing anyone opens.

Size: **two hours.**

### F7. Add the two missing buttons.

- `POST /api/campaigns/cancel-schedule` exists, is `@PreAuthorize`'d and audited.
  `console.js` calls `/schedule` and never this. **A campaign scheduled to a patient list
  by mistake can only be escaped by deleting the draft.**
- `POST /api/verification/cancel` exists with no button.

Size: **one hour total.**

### F8. Decide the inbound message log: wire it or hide the filter.

`MessageLogService.recordInbound` has **zero callers** anywhere (verified: the only
occurrence in `src/main/java` is its own declaration at line 96). `console.html:773` ships
an INBOUND option on the message-log filter. That filter can only ever return zero rows,
on a product whose stated use case is correspondence with hospitals and doctors.

Cheapest honest fix now: remove the option. The real fix is B12 (reply capture), which
gives `recordInbound` its first caller. Do not leave a filter on screen that cannot
populate.

Size: **ten minutes now, or defer to B12.**

### F9. Correct `UI-SPEC.md` 14.9 and 14.11, and `deploy.sh`.

See corrections 1 and 2 at the top. Additionally `deploy.sh` still checks for `journey%`
and `otp%` tables and prints "Journeys will be in the sidebar". It does not check
`queued_message`, `push_subscription`, `notification_rules`, `device_token` or
`mailbox_settings`. With `spring.jpa.hibernate.ddl-auto=update` (verified,
`application.properties:13`) and no Flyway, a failed table creation is a log line nobody
reads.

Also: `target/mailer-0.0.1-SNAPSHOT.jar` is stamped before HEAD. Three reader-rendering
commits are almost certainly not on production. Deploy HEAD.

Size: **two hours.**

### F10. Delete the dead surface, in one commit.

- 23 of 95 sprite symbols are unreferenced, and the set is a message: `i-strikethrough`,
  `i-code`, `i-align-left`, `i-align-center`, `i-indent`, `i-outdent`, `i-image`, `i-undo`,
  `i-select-all`. A richer composer toolbar was drawn and not built. Delete the symbols or
  build the toolbar; do not keep the ghost.
- `charts.js` defines `drawBars`, `drawGauge`, `drawSpark`. None is called.
- `Permission.SETTINGS_WRITE` exists solely to gate `/api/analytics/reclassify`, which
  nothing calls.
- Ten unused repository queries and twelve unused methods, listed in the state-of-product
  investigation.
- `/api/campaignsplus/templates*` (5 endpoints, `TemplateLibraryService` 347 lines)
  duplicates `/api/templates`. **Keep `TemplateLibraryService.validate`.** Its finding set
  (`UNSUBSCRIBE_MISSING`, `OVER_GMAIL_CLIP`, `IMAGE_ONLY`, `INSECURE_LINKS`,
  `UNBALANCED_MERGE_TAGS`, `NO_HTML_DOCUMENT`) is the right structural check list and is
  reused in B16. Delete only the API layer around it.
- `console.html` never includes `fragments/session :: surface`, so the session-expiry
  warning exists on the mailbox and not on Campaign Studio, which is where a long unsaved
  campaign draft lives. Include it.

Size: **two hours.**

### F11. Surface the pre-flight safety check.

`POST /api/campaignsplus/campaigns/{id}/safety-check` and `/drop-suppressed` have no front
end. The gate genuinely runs server-side on the send path (`CampaignService.java:113` calls
`safety.assertSafeToSend`), so the marketer meets it only as an error at the instant they
press Send, never as a pre-flight. Put the findings panel on the composer.

Size: **half a day.**

**Section 1 total: about four days.** Everything in it is finishing work someone already
paid for.

---

# 2. RANKED BUILD LIST

Value to a fifteen-person patient advocacy charity, divided by cost in this codebase.
Highest ratio first. Sizes assume one developer who knows the tree.

---

## B1. Audit every bulk export, and de-identify the audit log. 1 day.

**What.** Add `audit.record("SUBSCRIBERS_EXPORTED", ...)` with actor, filter parameters and
**row count** to all five CSV export routes. Change the audit `target` for subscriber events
from an email address to a subscriber id. Set a one-year retention on `audit_log`. Add a
reason prompt on exports above a threshold.

The five routes, all verified:

| Route | File | Permission | VIEWER can reach |
|---|---|---|---|
| `GET /api/audience/subscribers/export` | `controllers/AudienceApi.java:197` | `SUBSCRIBERS_READ` | **yes** |
| `GET /api/campaigns/{id}/export` | `controllers/CampaignApi.java:300` | `CAMPAIGNS_READ` | **yes** |
| `GET /api/analytics/segment/export` | `analytics/AnalyticsApi.java:141` | `ANALYTICS_READ` | **yes** |
| `GET /api/journeys/{id}/sheets/{bucket}/export` | `journey/JourneyApi.java:606` | `CAMPAIGNS_READ` | **yes** |
| `GET .../verified-clean export` | `verification/VerificationApi.java:180` | `VERIFICATION_RUN` | no |

**Why here.** Verified in the same file, twenty lines apart: `AudienceApi.java:179` audits
`SUBSCRIBER_DELETED`; `AudienceApi.java:197` audits nothing. There are 69 `audit.record`
calls in the codebase. The authors instrumented writes, because writes are scary.
Exfiltration is a read. The audience export takes `q`, `status` and `listId`, all optional,
all defaulting to empty, and walks the entire subscriber table in 500-row pages with no cap,
no rate limit and no reason field.

There is no compensating control: no `HandlerInterceptor`, no request-logging filter, no
access-log configuration. nginx has a request line if the log is retained and if anyone
thinks to look, but it carries no application identity and no row count.

DPDP Rule 6 will require "appropriate logs, monitoring and review, for enabling detection of
unauthorised access, its investigation and remediation", enforceable **14 November 2026**.
Five unlogged bulk-export routes is the textbook failure of that clause.

The consequence is not the fine. A file of several thousand cancer patients leaving the
building triggers Rule 7, which has **no materiality threshold**: preliminary intimation to
the Board without delay, detailed report within 72 hours, and individual notification to
**every affected data principal**. For this charity that means writing to several thousand
cancer patients to say a file disclosing their diagnosis is outside its control. With no log,
JCF cannot know it happened, cannot start the 72-hour clock, and cannot scope the notification.

**Touches.** Five controllers, `AuditService`, one scheduled purge job, one UI prompt.

---

## B2. Recipient-domain column and the per-provider panel. 1.5 days.

**What.** One derived column on `campaign_recipient`,
`lower(substring(email from position('@' in email)+1))`, indexed, backfilled. One grouped
query joining `global_suppression` and `tracking_event`. One screen: sent, bounced,
complained, human opens, human clicks, per gmail.com, yahoo.com, outlook.com and every
hospital domain.

**Why here.** Recipient domain is the axis along which every deliverability question is asked,
and **nothing in the system today can answer "what is our bounce rate at gmail.com"**. It is
not stored on `MessageLogEntry` and not on `CampaignRecipient`. This is where an Indian
charity's problems actually appear: a hospital gateway silently dropping them, a Gmail
reputation dip, a corporate domain at 40% bounce because someone imported a stale conference
list. It has no retention limit, because it does not touch `message_log`, which purges at 60
days (`application.properties:103`).

It is also step one of the board report (B11) and of the "we reached 61 addresses across 14
hospitals" sentence that no commercial tool will ever write.

**Print this caveat on the screen:** SES `Delivery` means the receiving server accepted the
message, not that it reached an inbox. Do not label any column "inbox".

**Touches.** `CampaignRecipient`, one repository query, one console panel.

---

## B3. Delete the metrics that contradict each other. Half a day. Pure subtraction.

The product currently publishes **three different open rates for the same campaign**.

**3.1 `openRate` on `/api/overview` and `openRate`/`clickRate` on `/api/campaigns`.**
`OverviewApi.java:74` and `CampaignService.java:434` compute these from the legacy
`openedAt`/`lastClickedAt` columns, all-time, denominator `sent`. `AnalyticsService.summary`
computes from `tracking_event` HUMAN rows, windowed, denominator `delivered`.
`OpenTrackingService.reclassify` rewrites `tracking_event` only, and its own comment concedes
the legacy columns are not retro-corrected, so the two families diverge permanently by design.
**Delete the dashboard open-rate tile. Serve the campaigns-table percentage from
`AnalyticsService` or delete it.**

**3.2 The composer's link table and the `ClickEvent` table behind it.** `CampaignApi.java:66`
calls `CampaignService.linkBreakdown`, which reads raw click rows with **no bot filtering at
all** (Proofpoint, SafeLinks and Slack unfurls included) and renders visually identically to
the filtered table on the analytics screen. Delete `ClickEvent`, `ClickEventRepository`,
`CampaignService.linkBreakdown` and the composer table; point the composer at
`/api/analytics/links`, which already exists and is HUMAN-filtered. **Bonus: this removes one
of the four tables that survive an erasure request (see B6).**

**3.3 `unfilteredOpens`, `unfilteredOpenRate`, `inflationFactor`.** Verified at
`AnalyticsService.java:66`: `allOpens = reliableOpens + mppOpens + botOpens + proxyOpens`,
where every term is a separate `count(distinct e.email)`. Anyone who opened once on an MPP
iPhone and once in desktop Gmail is counted twice. `unfilteredOpenRate` can exceed 100%, and
`inflationFactor` overstates exactly the correction it exists to demonstrate. Delete all three
and the console line at `console.js:2235`. Keep the per-bucket classifier table, which is
honest and does not sum across buckets.

**3.4 `clickToOpenRate`** (`AnalyticsService.java:103` and `:263`, plus
`JourneyService.variantReport`). The denominator is now HUMAN opens only, so it reads far
above every published CTOR benchmark, and the first thing anyone does with a CTOR is compare
it to one. Delete it or rename it to something nobody can benchmark.

**3.5 `complaintRate` shown against a hardcoded 0.1 danger line with no caveat.** Gmail runs
no per-message feedback loop; complaints reach senders only as aggregate spam rate in
Postmaster Tools, and only for mail carrying a `Feedback-ID` header, which `SesSender` does
not set. For a list that is probably mostly Gmail, a green 0.00% next to a red danger line is
not evidence of health, it is evidence of no signal. Relabel it "SES-reported complaints, does
not include Gmail" until B14 lands.

**3.6 `exclusiveTotal` on the segments screen.** In `SegmentRepository.PREDICATE`,
`PRIVACY_UNKNOWN` requires a non-HUMAN **open** event while `NOT_OPENED` requires no event at
all. A recipient whose only row is a non-HUMAN **click** (a security gateway followed the link
while images stayed blocked, the single most common corporate configuration) matches neither
of the four buckets, so the total silently comes in under `sent`. Widen `PRIVACY_UNKNOWN` to
any non-HUMAN event, or delete the total.

**3.7 The second unsubscribe number.** `SegmentRepository` defines `UNSUBSCRIBED` as
`reason not in ('BOUNCE','COMPLAINT')`, folding in manual admin suppressions.
`AnalyticsService.summary` counts an exact match on reason. Two counts, one campaign, two
screens. Pick one.

**3.8 `failedAllTime` and `skippedAllTime`** sit in a payload where every other number is
windowed, and the console renders tiles from values, not key names. Move them to their own call.

**Why here.** Two numbers with one name is worse than no number. This product's whole
differentiator is that it does not flatter the user, and it currently contradicts itself on
adjacent screens.

---

## B4. Preference centre: topics, pause, frequency. 5 to 6 days.

**What.** A public page keyed on the `unsubscribeToken` that already exists on `Subscriber`
(verified, `Subscriber.java:40`), so no new auth.

- **Topics, not lists.** A `topic` table plus a `subscriber_topic_optout` join, or a `topic`
  grouping on `MailingList`. Five to eight topics maximum: support group, treatment and
  nutrition information, events, volunteering, fundraising appeals, organisation news. Each
  with a plain sentence saying what it is and roughly how often it arrives.
- **Frequency, not just on and off.** "Everything", "monthly digest only", "urgent only".
- **A pause.** "Pause everything for 1 / 3 / 6 months." Store `pausedUntil` on `Subscriber`
  and have `CampaignService.queue`, `JourneyParticipantRepository.findAdmissible` and
  `JourneyService.runEmail` all treat it exactly like suppression.
- **Keep global unsubscribe as a plain, prominent link on the same page.** Never hide it
  behind preferences. That is a dark pattern and it breaks the one-click requirement.
- **RFC 8058 one-click must still resolve to a full global unsubscribe.** Do not try to make
  the header mean "topic-level". It means stop.

**Why here, and this is the argument that matters.** `TrackingController.java:69-81` has
exactly one unsubscribe and it is global: `suppression.unsubscribeByToken(token)`. There is no
way to say "stop sending me fundraising appeals but keep sending me the support group schedule."

A patient mid-chemotherapy who does not want another donation appeal has exactly one button,
and pressing it also cuts off the support group notice that is the reason they gave you their
address. The product currently forces a person at their most vulnerable to choose between being
solicited and being abandoned. Mailchimp's groups-plus-hosted-page equivalent is aimed at a
shopper choosing between "deals" and "new arrivals". The pause button in particular has no
commercial equivalent, because commerce ESPs have no concept of a customer who needs to step
back for three months and come back.

**On the "preference centres cut opt-outs 30%" figure: unverified.** Every citation traces to
vendor blogs quoting each other. Build this because of the paragraph above, not because of a
number.

**Touches.** `TrackingController`, `Subscriber`, two new tables, one new public template, the
send path in three places, `SecurityConfig.PUBLIC_PATHS`.

---

## B5. Consent record, import consent capture, and the section 5(2) legacy notice. 3 days code, plus one campaign.

**What.**

- One append-only table, never updated: `subscriber_id`, `list_id`, `event`
  (GIVEN / WITHDRAWN / CONFIRMED), `at`, `source_type`
  (WEB_FORM / CSV_IMPORT / MANUAL / MIGRATED_LEGACY / EVENT), `evidence` (form URL, filename
  or CSV row), `ip`, `user_agent`, `notice_version`.
- Backfill every existing subscriber as `MIGRATED_LEGACY` with the migration date.
  **Do not backfill them as consented.**
- Make CSV import **require** the operator to state the consent basis and paste the wording
  used, stamped on every row in that import. That single required field converts an import
  from a liability into a record.
- Add one BLOCK-severity finding to `SafetyCheckService`: block a send where more than N
  percent of the audience has no consent record. Reuse the existing BLOCK / WARN / INFO
  machinery, which is already trusted and already on the send path.
- **The legacy notice campaign needs no code.** DPDP section 5(2): for data collected before
  commencement you do not have to re-obtain consent, provided you issue a notice describing
  the data held, the purposes, how to exercise rights and how to complain to the Board. One
  privacy page, one campaign, sent with the tool they already have, before 14 November 2026.
  This is the highest value-to-effort action available to the organisation.

**Why here.** Verified: `Subscriber` has `source` (free text), `createdAt` (a row-creation
timestamp), and nothing else. The constructor sets `status = "SUBSCRIBED"` by field
initialiser, so **every address becomes mailable the instant the row is created**, whether it
arrived by CSV import, a manual add, or the Zoho migration. A recursive grep for consent terms
across `src/main/java` returns only `PersistentDeviceFilter`, `OtpService`, `MailboxSettings`
and one comment. `migrate-v2.sql` and `migrate-zoho.py` carry no consent columns, so nothing
was carried over.

**Double opt-in: per list, not blanket.** Correct for a public web form, where it stops typos,
bots and malicious third-party signups. Wrong for a patient who filled in a paper form at a
hospital camp and will never action a confirmation email. `MailingList.kind` already exists
(verified, defaults to `"IMPORT"`), so gate on it: default on for `NEWSLETTER` and `EVENT`, off
for `IMPORT`. The point is proof of consent, and a signed sheet is proof.

**There is currently no public signup path at all.** `SecurityConfig.PUBLIC_PATHS` contains only
tracking, unsubscribe, SNS and static assets. Every address enters by CSV import, which is the
single largest consent liability in the product. Add the public form here, with `EmailVerifier`
(already exists) doing the syntactic and MX work.

---

## B6. Real erasure, and a single-subject export. 2 days.

**What.** Replace `SubscriberService.delete()`, which is verified to be exactly two statements:

```java
@Transactional
public void delete(Long subscriberId) {
    listMemberRepository.deleteBySubscriberId(subscriberId);
    subscriberRepository.deleteById(subscriberId);
}
```

with a transaction that:

- hard-deletes `subscriber`, `list_member`, `click_event` (or, better, delete that table
  entirely per B3.2), `tracking_event`
- **pseudonymises `campaign_recipient` in place**: `email` and `name` overwritten,
  `subscriberId` nulled, counts retained so campaign analytics do not retroactively change
- **keeps the `global_suppression` entry**, because you cannot honour an unsubscribe for an
  address you have forgotten, and section 8(7) does not require erasing data retained to comply
  with a legal duty
- writes an erasure certificate row

Add `GET /api/audience/subscribers/{id}/full-export`: everything held on one person across
`subscriber`, `list_member`, `campaign_recipient`, `tracking_event`, `global_suppression`,
`journey_participant`, `journey_event` and the message log. There are five bulk exports for
staff and no route for a rights request.

**Why here.** Today the system reports a person deleted and keeps their name and email address
in three other tables, fully searchable. `campaign_recipient` carries `email` and `name` as
denormalised snapshot columns; `tracking_event` carries `email`, `ip` and `userAgent`. That is
not a partial erasure, it is a false report of erasure.

The pseudonymise-not-delete choice for `campaign_recipient` is the one design decision worth
arguing about, and it is the right one: it preserves the historical accuracy of every campaign
report while removing the identifier.

---

## B7. Contact timeline, with a note and a hold-until date. 3 days, mostly indexes.

**What.** `GET /api/audience/subscribers/{id}` plus one screen. A `UNION ALL` over five
already-indexed tables, shaped as `(occurred_at, kind, title, detail, campaign_id, ref_id)`,
paged on `occurred_at`, defaulting to a 12-month window with a "show everything" toggle.

Indexes needed: `campaign_recipient(subscriberId)` (today it is `(campaignId, status)`),
`tracking_event(subscriberId, timestamp)`. `message_log`, `journey_event` and
`journey_participant` need nothing. `transactional_log` joins on `lower(to_email)` or gets a
`subscriberId` on write.

Plus two things that are not email, and that turn a log into a case record:

- **A note, with author and timestamp.** There is no notes concept anywhere in the codebase.
  Five lines of schema. This is the field a caseworker will use most.
- **A "do not mail until `<date>`" hold**, honoured by `CampaignService.queue`,
  `findAdmissible` and `runEmail`. Same column as B4's `pausedUntil`; build it once.

**Read `tracking_event`, not the denormalised counters.** `OpenTrackingService.reclassify`'s own
javadoc concedes it rewrites `tracking_event.classification` and does not retro-correct
`Subscriber` or `CampaignRecipient` counters. Reading events also lets the timeline say "Apple
prefetched this" instead of claiming an open.

**Do not build a `contact_activity` table that everything writes into.** It is the obvious
design and it is wrong here: it doubles every write on a 1.8GB box, creates a second source of
truth that will drift from the tables analytics read, and buys nothing at a few thousand
contacts where a bounded, indexed `UNION ALL` is milliseconds. Materialise if it ever gets slow.
It will not.

**Why here.** `AudienceApi` has a subscriber list, create, delete, list-membership toggles and
export, and **no per-subscriber endpoint**. Every screen in the product is campaign-first:
analytics is per campaign, `SegmentService.summary(campaignId)` is per campaign, the message log
is a flat search. For fifteen people whose unit of work is one family, that is the wrong shape.
A caseworker about to phone a family cannot see that the mother got the fundraising blast
yesterday, that the last three sends bounced, or that someone replied a week ago and nobody
answered.

**Access control is a design requirement, not a nicety.** Gate the timeline to
OWNER/ADMIN/MARKETER, consider narrowing note bodies further, and write every view to
`AuditService`.

---

## B8. Shared inbox: internal comments, assignment, status. 4 days.

**What.** One JPA entity keyed on `(mailbox_address, thread_id, root_message_id)` with comment
rows, an `assignee`, and an open/closed `status`. One GET/POST pair. One panel in `mail.js`.
Plus a collision heartbeat row written when someone opens a conversation or the compose pane,
surfaced through the existing 45-second `MailPollApi` poll as "Priya opened this 2 minutes ago"
or "Priya is replying".

`threadId` is already fetched (`mail/MailService.java:44,48`), already on `MessageSummary` and
`MessageBody`, and already reaches the browser (`webmail/MailApiController.java:265,768`).
`ddl-auto=update` means new entities create their own tables. Zero JMAP change, zero Stalwart
change, zero migration.

**Mirror assignment into a JMAP keyword** (`jcf-assign-priya`) via `Email/set`.
`mail/MailService.java:922 normaliseKeyword` already permits arbitrary user keywords with no code
change. Treat the mirror as a convenience for other IMAP clients, **never as state you read
back**: Thunderbird has open bugs where assigning a tag never sends `STORE` and the tag reverts
on refresh (Mozilla bug 2057187, parent bug 336220).

**Notes live in Postgres only.** Say so in the UI in one line, once. Every competitor made the
same trade. Hiver stores assignments and notes in its own database with mail left on the
provider. Front's published contract is that read/unread, archive, tag changes, trash and snooze
do **not** sync back to Gmail, and tags sync once at import and never again. Nobody stores a note
in the mail server. Do not attempt a header (visible to anyone who hits "show original") or a
hidden draft (one accidental send from reaching a patient's family).

**Why here.** They have already hit the shared-inbox problem and solved the notification half of
it. `push/NotificationRules.java:33` exists precisely because a message to `support@` interrupts
three people, two of whom must not act. `models/MailboxSettings.java:34` reasons about "whichever
of the three people who share support@ opened". What is unsolved is the coordination half: two
people replying, nobody replying, and no place to write something down.

The sentence a colleague most needs to write is exactly the sentence that must never reach the
recipient: "her husband died last month, do not send the standard follow-up", "this family has
already been told, do not repeat the diagnosis". Today the only places to put that are a forward
or a reply-all. A reply-all mistake in a commercial support queue is embarrassment. Here it is
harm. **That, not efficiency, is what justifies building this.**

**Get the key right on day one.** See section 4.

---

## B9. Frequency cap, plus DECEASED and DO_NOT_CONTACT. 2 days.

**What.**

- A hard organisational cap: no more than N marketing emails per person per calendar month,
  default 2, counted from `campaign_recipient`, transactional and HR mail exempt. Enforce it in
  `SafetyCheckService` as a new BLOCK-severity finding listing how many people would be pushed
  over, with an explicit override written to the audit log. Klaviyo's Smart Sending uses a
  16-hour window; for this organisation the window is a month and the cap is the point.
- Add `DECEASED` and `DO_NOT_CONTACT` to the `status` enum (today:
  `SUBSCRIBED | UNSUBSCRIBED | BOUNCED | COMPLAINED`). Exclude both from every audience match,
  every journey entry, **and specifically from every re-engagement and sunset flow**.

**Why here.** With a journey engine, multiple lists and several staff who can send, nobody has a
view of what an individual actually received this month. The person on four lists gets four
emails and you find out when they complain. At low-thousands volume you only need a handful of
complaints to breach the 0.3% Gmail and Yahoo spam-rate ceiling, and that ceiling is the
enforcement mechanism for everything else in this document.

And: getting a "we miss you" re-engagement email addressed to someone who died is the worst thing
this software can do, and every commerce ESP does it by default, because none of them has a state
between "subscribed" and "unsubscribed" that means "this person is gone".

---

## B10. Handle `DeliveryDelay` and `Reject`, and stop discarding bounce fields. 1 day. Requires F1.

Verified: `SnsWebhookController.handle` has exactly three cases, `Bounce` (line 100), `Complaint`
(115), `Delivery` (125).

- **`DeliveryDelay` is the most valuable unhandled event in the system.** Its `delayType`
  includes `SpamDetected` ("the recipient's mail server has detected a large amount of
  unsolicited email from your account") and `IPFailure` ("the IP address that's sending the
  message is being blocked or throttled"). These arrive hours before the bounce rate moves.
- **`Reject`** with reason `Bad content` means SES found a virus in outgoing mail. Silence today.
- **Bounce loses almost everything.** `handle` passes `""` as the host, discarding
  `bounce.reportingMTA`, and synthesises `permanent ? 550 : 450` instead of reading the DSN's own
  per-recipient `status` (for example `5.1.1`) and `bounceSubType`. "The mailbox does not exist"
  and "the domain rejected our content" (`Transient/ContentRejected`) collapse into one row.
- **Complaint loses `complaintFeedbackType`.** `abuse`, `not-spam`, `auth-failure`, `fraud` and
  `virus` all become "Marked as spam by the recipient". An `auth-failure` complaint is a DMARC
  alarm, not a content problem, and the two need opposite responses.
- **Delivery loses `remoteMtaIp` and `processingTimeMillis`.** The latter, plotted per recipient
  domain over time, climbs before anything else moves when a provider starts deferring. No hosted
  platform shows you your own longitudinal per-provider latency.

Add three or four columns and about fifty lines. Also add `Subscription`, which catches
List-Unsubscribe header clicks the app's own endpoint never sees.

---

## B11. The board and donor report. 3 days, plus one enum on `campaign`.

**What.** One page, print-first HTML, no build step, prints cleanly from the console. Period
selector, generate, print. It is a document, not a dashboard.

Contents, in this order:

1. **Scale in one sentence.** "In this period we sent N messages to M distinct people." Not
   campaigns. Nobody outside marketing knows what a campaign is.
2. **The asset, and whether it is growing.** Mailable subscribers at period start and end;
   joined; lost, split by reason. Net change is the most important number in the document,
   because the list is the charity's only owned distribution channel. Free from
   `subscriber.createdAt` and `global_suppression`.
3. **Confirmed human engagement, as people.** Distinct people who clicked, distinct people with a
   verified human open, both as counts with the mailable base beside them. Never as a rate on the
   front page.
4. **Grouped by purpose, not campaign name.** This is the one schema addition: a `purpose` enum on
   `Campaign` (fundraising, programme update, event, patient support, volunteer, transactional).
   Verified: `Campaign` has no such field today. "Fundraising appeals reached 1,840 people, 96
   clicked" is a board sentence. "Diwali Newsletter v2 FINAL" is not.
5. **What people actually asked for.** Top links by unique human clicks, destinations named in
   plain language: "Patient support helpline: 214 people. Donate page: 96 people."
6. **Reach into the constituency that matters.** Institutional versus consumer domains, from B2
   plus a hand-maintained hospital domain list. "We reached 61 addresses across 14 hospitals" is a
   sentence a board remembers and Mailchimp will never write.
7. **Reliability and safety in plain English**, each against its line, with a verdict sentence:
   "We are within the limits that keep us permitted to send."
8. **A printed limits paragraph, verbatim, not a tooltip.** Opens are a range because Apple's
   privacy protection loads images whether or not anyone reads; we cannot see whether mail landed
   in spam; we cannot measure reading. The API already carries this text as `readingCaveat`,
   `attribution` and `basis`. Promote those strings into the printed page.
9. **A narrative box.** Two or three sentences typed by whoever runs the programme.

**Explicitly not in it:** open rate as a headline, click-to-open ratio, device split, time-to-open,
per-campaign performance tables, industry benchmark comparisons.

**Why here.** Mailchimp and Klaviyo report per campaign because their customers run campaigns for a
living. A board meets quarterly and asks about a period, a programme and a number of people. No
commercial tool produces that document, so charities produce it by hand, badly, by pasting
screenshots of open rates into a slide.

**On money.** M+R's revenue-per-1,000-emails is the sector's shared language and a donor report
without a money line is weaker. But nothing in this app collects a donation. **Do not fake it with
an estimated value per click.** Either wire the donation platform's callback and report real
attributed gifts, or leave money out and say so in the limits paragraph.

---

## B12. Reply capture via per-recipient Reply-To. 2 to 3 days.

**What.** Send campaign mail with `replies+<token>@jarurat.care`, where `token` is the existing
unique, indexed `campaign_recipient.token`. `SesSender.build()` already sets `replyToAddresses`,
so this changes the value passed, not the send path. Stalwart supports subaddressing per domain.
Then `MessageLogService.recordInbound` gets its first caller, a REPLIED bucket lands on the
`promoteBucket` ladder above CLICKED, and the `REPLIED` journey condition fires for the first time.

**Do not use the `In-Reply-To` matching route without testing it first.** AWS documents that SES
overrides any supplied `Message-ID`, and that `Message-ID` is a disallowed custom header for
`SendEmail` with Simple content, which is what `SesSender` uses. Whether a real reply's
`In-Reply-To` can be normalised back to `sesMessageId` is unverified and needs one live test.
Subaddressing is exact and survives forwarding.

**More important than the trigger: a reply must suppress the rest of the journey by default.**
Continuing a scripted nudge sequence at someone who has just written about their father's diagnosis
is the worst thing this software can do.

**Why here.** A reply is the strongest possible evidence of both inbox placement and human
engagement, stronger than an open (which MPP has destroyed, and which `OpenClassifier` already
correctly refuses to trust) and stronger than a click. **They run the inbound MX for the same
domain, so no ESP on earth can see this signal and they can.** One piece of work serves the journey
engine, the contact timeline, the deliverability picture and the message log's dead INBOUND filter
simultaneously.

---

## B13. Interval open rate everywhere, and confidence bounds on every rate. 1.5 days.

`SegmentService.summary` already computes `openRateLower` and `openRateUpper` and correctly reasons
that "the honest open rate is a range, not a number". It gates presentation on `showAsRange` and
lives only on the per-campaign segments screen. `AnalyticsService.summary`, which drives the main
analytics screen, the dashboard sparkline and `byCampaign`, publishes a scalar. **The product knows
the honest answer and publishes the dishonest one, on adjacent screens.**

1. Kill the scalar. `AnalyticsService.summary` and `byCampaign` return
   `openRateLower`/`openRateUpper` and no `openRate`. Remove `showAsRange`: the interval is always
   the presentation, because a narrow interval is itself the information ("this audience is barely
   on Apple Mail").
2. Attach a confidence bound to every published rate, reusing the power maths already in
   `JourneyService`. Render "3.1% plus or minus 1.2", or grey the rate out below a denominator floor.
3. Promote MPP share to a data-quality indicator at the top of the screen, framed the way a survey
   reports response rate, not buried in a classifier bucket table.

**And fix the MDE formula while you are in there.** Verified at `JourneyService.java:757`:

```java
out.put("detectableDifferencePoints", smallestArm == 0 ? null
        : Math.round(100 * 2.8 * Math.sqrt(2 * 0.25 * 0.75 / smallestArm) * 10) / 10.0);
```

That pools the variance for both the alpha and the beta term, which is optimistic by about 6%: at
n=300 it reports 9.9 points where the exact figure is 10.5; at n=1000, 5.4 against 5.6. Replace the
second term with `sqrt(p1(1-p1) + p2(1-p2))`, or add 6%. Small, but it under-reports blindness in
the one component whose entire purpose is not to flatter the user.

**Also: the dashboard headline should be a count of people, not a rate.** "Active audience: 610 of
2,140 people did something verifiable in the last 90 days", with bounce rate and complaint rate
beside it. A count does not move when Apple ships an OS or a hospital installs Mimecast. Every rate
does. And click rate alone is the wrong headline here even though it is right for commerce: a large
share of this organisation's mail has nothing to click, and a click-rate headline would make its
most important correspondence look like a failure.

---

## B14. Small deliverability items, done in passing. 1 day for all four.

- **`Feedback-ID` header** on campaign sends: a stable 5 to 15 character sender identifier plus the
  campaign id. SES signs after the header is added, so it satisfies Gmail's requirement. Five lines.
  **Do not build a dashboard for it.** At this volume the FBL will show nothing. The point is that
  without the header, Gmail reports no complaint data to you at all.
- **Register the domain in Google Postmaster Tools.** One TXT record, five minutes. Glance at the
  compliance dashboard monthly in a browser. Do not build against the API (see cut list).
- **Alarm on the Stalwart relay route.** `StalwartDeliveryLog` already parses the `hostname = "..."`
  field on every delivered line. If it is ever not an SES endpoint, something has changed and mail
  is leaving from an IP with an `ec2-*.compute.amazonaws.com` PTR. Five lines, in a class that
  already has the data in hand.
- **Rendering preview.** The existing `/{id}/rendered` view, plus the plain-text part that
  `toPlainText` already generates and nobody ever looks at, plus a narrow-width iframe, plus a
  dark-mode render. An afternoon, and it catches most real breakage.

---

## B15. Retention jobs on the behavioural tables. Half a day.

`MessageLogService.purgeExpired` runs at 60 days. `tracking_event` has **no retention job at all**,
only `deleteByCampaignId`. So the table holding subject lines is pruned, while the table holding
**IP addresses and user agents of cancer patients** grows forever on a 1.8GB box.

- Null `tracking_event.ip` after 90 days, keeping `classification`, `reason`, `client`, `deviceType`
  and `secondsSinceSent` so `reclassify` still works on everything except the IP signal, which the
  classifier already treats as corroborating rather than decisive.
- Purge `tracking_event` rows at 18 months.
- Pseudonymise `campaign_recipient` snapshots older than three years.
- Same pattern as `purgeExpired`. Privacy improvement and capacity fix in the same twenty lines.

---

## B16. Deterministic pre-send checks, and a minors flag. 1 day.

**Keep `TemplateLibraryService.validate`** and add three deterministic checks to it:

- does a meaningful plain-text part exist
- do all link domains resolve, and is none of them on the Spamhaus DBL (a free DNS lookup at this
  volume, and AWS's own guidance points senders at exactly this)
- is the reply-to reachable

**Do not produce a score number.** A number invites optimising the number, and Gmail, Yahoo and
Outlook do not use SpamAssassin.

**Minors flag.** DPDP section 9(3) is an absolute prohibition on tracking, behavioural monitoring
and targeted advertising directed at under-18s, which no parental consent can cure. The Fourth
Schedule exemption is for clinical establishments processing "restricted to provision of health
services to the child"; a patient advocacy charity is almost certainly not one, and marketing mail
is not that. There is no date of birth, age or child flag anywhere in the schema, so JCF cannot
currently determine whether it is tracking a sixteen-year-old patient. Paediatric and adolescent GI
cancer is rare but not zero.

The fix is cheap because `Campaign` already has `trackOpens` and `trackClicks` booleans (verified,
`Campaign.java:44-45`): add a "may contain minors" flag on `MailingList` that forces both off and
excludes the list from `EngagementSegment` cohorts. About thirty lines, and it converts an absolute
prohibition into a non-issue.

---

## B17. Broadcast subject-line A/B, 50/50, no winner promotion. 2 days.

Extend `variantReport`'s pattern out of the journey SPLIT node to ordinary broadcasts.

The arithmetic, computed for a 25% open baseline at 95% confidence and 80% power:

| Effect to detect | n per arm | Total list needed |
|---|---|---|
| +10 points (25 to 35) | 329 | 658 |
| +5 points (25 to 30) | 1,251 | 2,502 |
| +3 points | 3,397 | 6,794 |

A 3,000-person list split 50/50 with no holdout sees a 4.6-point open-rate difference. **The
industry-standard 10% holdout test on the same list needs a 15.1-point swing**, because 150 per arm
sees nothing.

So:

- **50/50 on the full list, no holdout.** Both halves get mailed, both are real sends, you learn
  afterwards. The list is small enough that the "test then send winner" pattern is what destroys the
  power.
- **Subject lines only. Do not offer a click-rate test.** Detecting a click improvement needs 5,000
  to 20,000 per arm. If you offer it, people will use it and read noise as signal.
- **Tell the user before they run it.** On the compose screen: "Your list is 2,840. Split in half,
  this test can see a difference of about 4.8 points in open rate. Anything smaller will look like a
  result and will not be one." That sentence is worth more than the feature.
- **Below 660 recipients, block the toggle.** Not a warning. Warnings get clicked through.
- **Never name a winner, and never auto-send one.**
- **Keep a test log.** Five individually underpowered subject tests over a year are worth something
  together: "short subjects won 4 of 5". Nobody else builds this because nobody else has customers
  this small. It is genuinely the advantage of being this size.

---

## B18. Cross-campaign rule segments, from a fixed catalogue. 3 days.

"Clicked any link in the last 90 days", "opened 3 of the last 5", "clicked a link whose URL contains
`/support-group`", "on list X but not list Y", "no verified engagement in N days".

This is a saved query over `campaign_recipient` plus `tracking_event`, not a new subsystem. It needs
the `tracking_event(subscriberId, timestamp)` index from B7. `SegmentService` already turns
campaign-level segments into saveable mailing lists, which is the right primitive; it is just
`summary(campaignId)`-shaped and campaign-scoped today.

**Four to six condition types, AND/OR, previewed count before saving. Not a free-form builder.**
listmonk's answer is to let admins write raw SQL against the subscribers table, which is elegant for
a developer-operated tool and wrong for a charity where the person segmenting is a programme manager.
`ConditionType` is deliberately a fixed catalogue and the reasoning is written into the file: every
entry maps to one predicate over data the platform already collects, and every entry has a defined
answer when the signal has not arrived. Extend the catalogue; keep it fixed.

**Why here.** "Everyone who has ever clicked something about nutrition" is a real, actionable
audience for a cancer charity, and today it cannot be built.

---

## B19. Typed custom fields, then the date and inactivity journey sources. 6 to 8 days, as one unit.

These are one item because the sequence is forced. Build in this order.

**19a. Journey re-entry key. Half a day, and it gates the other two.**
`uk_journey_subscriber (journeyId, subscriberId)` plus the `not exists` clause in `findAdmissible`
mean one person, one journey, forever. Add `cycleKey` to the constraint and to `findAdmissible`,
defaulting to a constant for list sources so nothing existing changes behaviour. `iterationNo`
already proves the pattern works, since it is how loops dodge the `campaign_recipient` unique
constraint.

**19b. Typed custom fields. 4 days.**
Verified: `ImportProfile.TARGET_FIELDS` and `CampaignService.SENDABLE_FIELDS` are the same six
fields, and `Subscriber` has exactly those six usable columns. Every other CSV column is discarded
at import, and `SesSender.applyMergeFields` substitutes an unknown tag with the empty string,
silently, for every recipient.

- **Declare fields, do not discover them.** A `custom_field` table (key, label, type, options,
  required, archived), key normalised to `A-Z0-9_` and unique on the normalised form so casing can
  never fork a field. Klaviyo lets a CSV header become a property and their own docs carry the
  consequence: "VIPStatus" and "vipstatus" are different properties. Copy HubSpot, not Klaviyo.
- **Seven types: TEXT, LONG_TEXT, NUMBER, DATE, BOOLEAN, SINGLE_SELECT, MULTI_SELECT.** DATE pays
  for the whole feature, because it unlocks 19c.
- **Prefer a select over free text.** The highest-value rule in this item. "Stage", "hospital",
  "language", "relationship to patient" as free text will arrive as forty spellings of six things
  and no segment will ever be right again.
- **Storage: JSONB on `subscriber`, not EAV.** One row per person stays one row, and the merge map
  is the single read `mergeFieldsFor` already does. The `custom_field` table stays the authority on
  legal keys and types; JSONB is only where values live.
- **Coerce in exactly one place**, called by both the CSV importer and the API write path. Reject
  rather than coerce silently.
- **The declared key is the tag.** `{{APPOINTMENT_DATE}}` and nothing else. No second syntax.
  `MergeTags` exists specifically because the pattern was once copy-pasted into `SesSender` and
  `TemplateApi` and drifted.
- **A per-field default, and a hard block on send.** Today an unresolved tag becomes `""` for
  everyone, so "Your appointment is on {{APPOINTMENT_DATE}}" ships as "Your appointment is on ."
  Upgrade `SENDABLE_FIELDS`' warning to a BLOCK with a count and a sample. On a list of two thousand
  this is fixable, and the marketer corrects the CSV. Mailchimp can only warn because at their scale
  it is not fixable. That is a genuine advantage of being small.
- **Format DATE once, centrally.** Default to "12 Sep 2026". A raw ISO date in an Indian charity's
  email is wrong, and `MergeTags.sampleFor` already sets that precedent.
- **Ceiling of 25 fields, archivable not deletable, with an "unused since" column.**
- **Leave the subject/body escaping split exactly as it is.** Custom fields flow through both paths,
  and `SubjectRenderingTest` is the test that stops someone unifying them again.

**19c. Rule sources on the journey engine. 3 days.**
One new SOURCE kind, evaluated on the tick that already runs every 60 seconds. Because admission is
already a poll, this swaps `join list_member` for a predicate inside one query, not a new subsystem.

- **Date source.** Appointment date, treatment start, follow-up due, volunteer induction, donation
  anniversary. Fire on or a configurable interval before the date; repeat yearly, monthly or never;
  one anchor per journey, not editable after the fact. **Reject epoch zero and anything before 1900
  at the import boundary, not at send time**, or a default timestamp becomes 1 January 1970 and a
  yearly campaign fires for that person every New Year's Day.
- **Inactivity source.** `lastEngagedAt` is already maintained and already MPP-filtered
  (`OpenTrackingService.recordOpen` only calls `campaigns.trackOpen` when
  `verdict.classification().countsAsEngagement()`), so it is an honest column. Needs an index on it.
  The honest predicate is: **no verified open AND no click in N days AND was actually sent something
  in that window.** The last clause is not pedantry; without it a sunset journey mails the people you
  have been ignoring and calls them disengaged. **Use 12 months and 6+ sends, not the standard 6
  months**: somebody in active treatment may not open anything for a year and still very much want to
  be on the list. Route the exit to the preference centre (B4), not to a "we miss you" beg. Add a
  cooldown so the same person is not re-admitted every quarter forever.
- **Manual and API admission.** A caseworker adding one family from the contact screen, and
  `POST /api/v1/journeys/{id}/enrol` for the website form. Both trivial once 19a exists, and both
  get used constantly.
- **Re-check the source predicate before each email node.** The engine already does the important
  half, since `runEmail` re-checks suppression late. It does not re-check that the source rule still
  holds. For a date journey, the appointment moving from the 12th to the 20th after admission is the
  normal case, not the edge case. About 30 lines given `route()` and `moveTo()` already exist.

**Before building the inactivity source, measure the cold cohort.** At low thousands mailed
occasionally, "no engagement in 12 months" may be twenty people, in which case this is a suggestion a
human confirms, not a feature.

---

## B20. Template library first, block composer only if it proves insufficient. 1 week, then measure.

**Ship 6 to 8 genuinely good pre-built templates first**: newsletter, event invite, appeal, volunteer
call, transactional, plain-text-style letter, patient information sheet. For the first year the staff
will use the library and never touch a builder.

**If after six months the templates do not cover every send**, build a **block-assembly composer, not
a canvas**:

- 8 to 12 blocks defined server-side as HTML fragments: header with logo, heading, paragraph, image,
  image-plus-text, button, divider, two-column, quote, event details, footer.
- The composer is a **vertical list**, not a canvas: add block, move up, move down, delete, edit
  fields. No drop targets, no coordinates, no z-index, no collision detection. That removes about 70%
  of the code and 95% of the bugs.
- Each block edits through a small form, **not contenteditable**. Contenteditable is where email
  builders go to die.
- The document is a JSON array of blocks rendered to table-based HTML by Java at send time, so
  `TemplateLibraryService.validate`, the Gmail clip check, merge-tag scanning and the preview all keep
  working unchanged.
- **Keep the raw HTML textarea** (`$('cHtml')` in `console.js`). It is the escape hatch anyone
  importing a designed template needs. Syntax highlighting, if wanted, is a ~120-line regex highlighter
  over a `<pre>` behind a transparent textarea. No library.

Realistic cost for the composer: 800 to 1,200 lines of JS, 400 lines of Java, no new dependency, no
CSP change, no Node.

**Why not a real drag-and-drop builder: see the cut list. The arithmetic is decisive.**

---

## B21. WhatsApp, as a utility and inbound channel only. 2 weeks paperwork, 2 weeks build.

Highest reach of anything in this document, and the one item with a real external dependency and a
real policy risk. Read the cut-list entry on WhatsApp broadcast before starting.

- **A WhatsApp number field with its own consent record**, separate from email consent, with timestamp
  and the exact opt-in wording shown. Meta's policy requires opt-in specific to WhatsApp; an email
  subscriber has not consented to it.
- **A template registry** mirroring approved Meta templates with placeholder mappings, so staff pick a
  template rather than type. You cannot free-type a broadcast; every marketing or utility message
  outside the 24-hour window must be a pre-approved template.
- **A send-utility-template action as a journey node type.** The journey engine already materialises
  sends as campaigns; a WhatsApp node fits that shape.
- **A BSP adapter behind an interface**, because you will change BSP.
- **Nothing else. No inbox, no chat UI.** The staff already use WhatsApp on their phones. Building an
  inbox for fifteen people is months of work to replace an app they already have open.

**The economics, and they decide the shape.** Rate-card figures below are from Indian BSP blogs, not
from Meta, so treat them as approximately right rather than exact:

| | per message | with 18% GST | vs email |
|---|---|---|---|
| Marketing template | ~Rs 0.8631 | ~Rs 1.02 | **~115x** |
| Utility template | ~Rs 0.1150 | ~Rs 0.136 | ~15x |
| Utility inside an open 24h window | free | free | free |
| Service, non-template, in window | free | free | free |
| Email via SES | $0.0001 | ~Rs 0.009 | 1x |

One marketing broadcast a month to 3,000 contacts is roughly Rs 50,000 to 60,000 a year including BSP
fees. The same 36,000 emails are about Rs 320 a year. There is **no nonprofit discount and no NGO
tier** for the WhatsApp Business Platform.

**Templates must carry zero clinical content.** "Your appointment at {{1}} is confirmed for {{2}}."
"The {{1}} support group meets on {{2}} at {{3}}." None of these state a diagnosis. All are utility
category.

**The real product is the free 24-hour window.** A "Message us on WhatsApp" link on the site and in
every email opens a window in which all your replies are free and untemplated. For a charity whose
whole function is answering worried families, that is worth more than every broadcast feature in this
document.

---

## B22. DMARC report reader. 3 days. Requires F2 to have been live for 60 days.

A scheduled poll of the `dmarc@` mailbox, gunzip, parse about fifteen fields, one table, one screen
showing source IP, volume, SPF result, DKIM result and alignment.

Cost is low **here specifically** because every hard part exists: a mailbox on Stalwart,
`JmapClient.download(user, blobId, name, type)` for the attachment, `GZIPInputStream` and an XML parser
in the JDK. No new dependency, no external request, CSP-clean, ships in the same jar.

**This is the one place where building clearly beats buying.** Commercial DMARC platforms charge
monthly for a parser and a table, and this organisation already owns both halves.

It gates the `p=quarantine` decision. Do not move policy without it. See section 5, R15.

---

## B23. Canned responses in webmail. 1 day.

`EmailTemplate` and the whole `merge` package already exist, with `SubjectRenderingTest` already
guarding the escaping rules. This is a UI affordance over data the app already stores, not a new
subsystem. For HR answering the same six volunteer questions, it is the item they will notice first.

---

## B24. First tests for `campaignsplus` and `sns`. 1 day.

Zero test files exist for `campaignsplus` (10 classes, 2,467 lines: CSV import, audience matching,
safety checks) or `sns` (2 classes, including `SnsMessageVerifier`, a signature-checking security
boundary on a public unauthenticated endpoint). `services` has one test file for seven classes
including `CampaignService` and `SesSender`.

The 505 green tests are real, and they are concentrated on the last ten commits' mailbox work. The two
places where a defect is unrecoverable are CSV import, which writes the subscriber base, and the SNS
webhook, which is public. Neither is covered.

---

# 3. CUT LIST

This section is worth more than the build list. Most of Mailchimp exists because Mailchimp has millions
of customers, and a feature that pays for itself across a million accounts does not pay for itself
across one charity with a few thousand subscribers. Do not soften any of this.

## Cut because the arithmetic does not work at 3,000 subscribers

| Cut | Why |
|---|---|
| **Multivariate testing** | Four cells on 3,000 people is 750 each: a 6.5-point MDE on opens and nothing at all on clicks. Do not build a three-arm test either. |
| **Click-rate or revenue A/B** | Needs 5,000 to 20,000 per arm. At no list size JCF will ever have is this measurable. |
| **The 10% holdout with auto-promoted winner** | 150 per arm needs a 15.1-point open swing to see anything. Built for lists where 10% is 10,000 people. Actively harmful here: a machine for making decisions on noise. |
| **Send-time optimisation** | Every number in circulation ("up to 25% lift") traces to vendor marketing. Mailchimp's own STO page publishes no minimum contact count and no accuracy figure; Klaviyo requires 12,000 active profiles before it will run. Per-recipient STO needs a per-person open-time distribution, and MPP prefetch has already destroyed that timestamp. The existing quiet-hours window (21:00 to 08:00 IST) is the whole of the honest version. |
| **Predictive anything** | Churn risk, lead scoring, AI subject-line scoring, lookalikes. All need tens of thousands of labelled outcomes and all produce a confident number from noise. And scoring a patient's family on engagement, then mailing the high scorers more, is the opposite of what this organisation is for. |
| **Cohort retention grids, click maps, read-time buckets** | See below. |
| **Industry benchmark comparisons** | They would compare a HUMAN-only open rate against a published figure that includes MPP. Structurally misleading in a product whose whole point is not being misleading. |

## Cut because the cost is wrong for this codebase

**A drag-and-drop email builder.** The arithmetic, measured against the actual files: GrapesJS 0.23.6
`dist/grapes.min.js` is 1,151,347 bytes raw and 294,760 gzipped, plus 12,508 gzipped of CSS. That is
about 307 KB gzipped of third-party code to vendor and own forever, on a front end that is 17,381 lines
total including all templates and CSS. It roughly doubles what the browser downloads. It also calls
`new Function(s,'_',i)` in its lodash template compiler inside a try/catch that **rethrows**, so it does
not degrade, it breaks, without `'unsafe-eval'` in `script-src`. On an app that renders arbitrary
customer HTML in a preview iframe, weakening `script-src` is the last thing you want. And GrapesJS alone
does not emit email HTML: you need MJML, whose compiler is Node, on a Java 21 box, which means adding
Node to a 1.8 GB t4g.small already running Postgres, Stalwart and a Spring Boot jar. That is the build
step the whole project was designed to avoid, arriving through the back door. Writing one yourself is
4,000 to 6,000 lines of vanilla JS plus a server renderer, then yours to maintain against every Outlook
rendering bug forever. **listmonk does ship one, and listmonk is a Go binary with a full Vue build
pipeline. It has a bundler. That is how it affords the builder.** See B20 for what to build instead.

**A landing page builder.** JCF has a website. This is a second CMS to maintain. Link to the site.

**Any Postmaster Tools API integration.** v1 was retired 30 September 2025, IP and domain reputation
were removed in v2, and at this volume Google withholds data on low-volume days, so the integration
would render an empty chart most of the time. Register the domain (one TXT record) and glance at the
compliance dashboard monthly in a browser.

**Real-time shared drafts with co-editing.** Missive's flagship feature, and a CRDT problem. Nothing in
this app has a real-time layer. At this size the substitute is turning around and asking.

**SSE or WebSocket for collision detection.** The 45-second `MailPollApi` poll already exists and can
carry a heartbeat. A 45-second-stale "Priya opened this 2 minutes ago" banner prevents most double
replies at a fraction of the cost of adding a real-time layer to a 1.8GB box under this CSP.

**Migrating `ddl-auto=update` to Flyway.** Correct in principle. On one box with one deployer and a
`pg_dump` in `deploy.sh`, it buys less than any item in section 1. Revisit when a second person deploys.

**Any separate analytics store, OLAP layer or materialised warehouse.** Postgres on a t4g.small handles
a few thousand subscribers and a few hundred thousand tracking rows without help. `MAX_WINDOW_DAYS` and
the paged `reclassify` already show the right instincts.

**Reverse ETL, warehouse sync, CDP integration.** There is one Postgres, on the same box as the mail
server.

**Outbound webhooks.** Defer, do not cut. Cheap and an honest escape hatch, but nothing consumes it
today and doing it properly (retries, signing, dead-letter) is more work than it looks on 1.8GB. Build
it when something asks.

## Cut because it is a different product

**Custom objects and associations.** What they would model here is a treatment or a case as a
first-class record. That is a patient management system. If JCF needs one, buy or build one; do not
grow it sideways out of a mailer. HubSpot gates these at Enterprise, from $3,600/month.

**Ecommerce triggers wholesale.** Abandoned cart, browse abandonment, back in stock, product
recommendations, order and shipping confirmations, review requests, RFM. Roughly half of Mailchimp's
catalogue. There is no store, no cart, and no product.

**Revenue attribution dashboards.** Until something collects a donation, this is a dashboard of zeroes.
Do not fake it with an estimated value per click.

**RSS-to-email.** Only if jarurat.care has an active blog with a feed. If it does, it is about 150
lines: poll feed, template the items, fire a campaign. If it does not, it is dead weight. Check before
deciding.

**Lifecycle stages as a subsystem.** Already built. `promoteBucket` is a forward-only rank ladder that
refuses any candidate ranked at or below the current value, which is HubSpot's lifecycle semantics
including the awkward part. If a global stage is wanted, it is a SINGLE_SELECT custom field, not eight
default stages plus a stage-change history table plus a funnel report.

**A visual segment builder with arbitrary AND/OR nesting.** `ConditionType` is a fixed catalogue on
purpose, and the reasoning is in the file. A free-form builder throws away both properties and hands a
fifteen-person charity a way to build a segment nobody can explain. Extend the catalogue.

**Approval workflows, teams, territories, deal pipelines, sales sequences, ABM, multi-tenancy,
SSO/SAML.** Six roles for fifteen people is already more structure than the org has. This is the
clearest case of features that exist because enterprise buyers have compliance departments.

**Per-agent leaderboards and volume-by-person analytics.** Actively harmful here. It will push someone
to rush a reply to a family that just got a diagnosis. Build first-response time for the **inbox**,
never for the **person**.

**SLA timers and escalation.** Fifteen people who sit together have no SLA. A timer that emails someone
because a reply is four hours old manufactures guilt, not throughput.

**Round-robin and load-balanced assignment.** A routing engine for a team that shares a room.

**CSAT ratings, help centre, knowledge base, AI drafting.**

**A unified multi-channel inbox (SMS, WhatsApp, chat).** This is where Front's price comes from, and
where the complexity comes from.

## Cut on deliverability specifically

**BIMI.** $650 to $1,688 a year for a logo, requiring DMARC enforcement first, and Apple still demands
the pricier VMC while Yahoo shows a logo with no certificate at all. For an organisation whose entire
SES bill is single-digit dollars a month, this would be the largest line item in the mail budget and its
whole benefit is cosmetic. Revisit only if someone is actively spoofing them **and** enforcement is
already live.

**A dedicated SES IP.** $24.95/month/IP, and it needs sustained volume to warm and to **stay** warm. At
low thousands per campaign they would never send enough, and a cold dedicated IP is strictly worse than
a shared pool. This also settles SNDS permanently.

**Microsoft SNDS.** Not available on shared SES IPs, because Amazon is responsible for those IPs, not
this charity. Microsoft's exact eligibility wording is unverified; their FAQ redirects and the landing
page says only "the IPs for which you are responsible".

**Reverse DNS on the Elastic IP, as a priority.** The box never opens an SMTP connection to a receiving
server: EC2 blocks outbound port 25 (verified by their own team on 2026-08-25 against
`gmail-smtp-in.l.google.com:25`), Stalwart relays to SES on 587, and campaign mail goes to the SES API
over HTTPS. So for every message leaving the organisation, the PTR Gmail checks is Amazon's. Requesting
it is a free form submission and it retires a "known gap" paragraph from two documents, so do it
eventually, but **rank it below every item in section 2.** The thing that actually matters is B14's
alarm on the relay route.

**Inbox placement and seed list testing, in any automated form.** Seed accounts never engage, and
Gmail's filtering is engagement-personalised, so the documented failure mode is the false positive:
green in the seeds, broken for real patients. **The version worth having is not software.** It is three
or four real accounts (a Gmail, an Outlook, a Yahoo, and whatever Indian provider their audience
actually uses) that staff genuinely open and read, added to every send as ordinary recipients. Costs
nothing, produces a better signal.

**A spam score number.** Gmail, Yahoo and Outlook use proprietary systems, not SpamAssassin. The
structural checks that are real are already in `TemplateLibraryService.validate`. See B16.

**A warm-up schedule.** Warm-up is an IP reputation concept and they do not own an IP. Domain reputation
is built by sending mail people want, not by a ramp.

**Spam-trigger-word lists and image-to-text ratio rules.** Folklore. Sending "free" in a subject line
from a domain with a clean reputation lands fine; the word only bites when reputation is already shot.

**A `mailto:` List-Unsubscribe alongside the HTTPS one.** Already done properly. `TrackingController`
exposes both a GET and a POST `/unsubscribe`, `SecurityConfig` line 364 CSRF-exempts exactly that path,
and `SesSender.build` sets both `List-Unsubscribe` and
`List-Unsubscribe-Post: List-Unsubscribe=One-Click`. Nothing to change.

**A second sending provider or self-hosted sending.** SES production access at 50k/day on a list in the
low thousands is 25x headroom.

## Cut on compliance specifically

**A DPDP consent-manager integration.** Aimed at registered intermediaries handling consent at scale.
Enormous work, zero risk reduction at a few thousand subscribers.

**Field-level access control.** The organisation is fifteen people, and the sensitive field is `email`,
which every function needs. The `Permission` enum with 25 rights and five roles is already more granular
than most fifteen-person orgs keep coherent, and the HR role's exclusions are genuinely well reasoned.
**Spend the effort on logging who reads, not on subdividing what they read.** That is B1.

**Third Schedule pre-erasure notices.** Rule 8's 48-hour notice and three-year cap attach only to
e-commerce with 2 crore users, social media with 2 crore users, and online gaming with 50 lakh users.
JCF is none of them.

**A DPO in India, algorithmic audits, data localisation, annual DPIAs.** All Significant Data Fiduciary
obligations, keyed to the 13 May 2027 phase. JCF is not an SDF at any plausible list size. Cut entirely.

**A GDPR compliance subsystem.** Consent records, erasure and a preference centre satisfy the
overlapping 80%. If the EU-resident population is under a hundred people, tag them and suppress
tracking; do not build an Article 27 representative arrangement.

**A DND / NCPR scrubbing feature.** TCCCPR 2018 and the DLT regime cover voice and SMS over telecom
resources. Email does not travel that pipe. A DND scrub would check a registry that does not contain
email addresses. Caveat: no express clause excluding email was found; the scope is defined positively.
If JCF ever adds SMS, the entire DLT regime lands at once, which is a further reason to prefer WhatsApp
over SMS.

**SMS as a channel.** India requires TRAI DLT registration: Principal Entity registration, a 6-character
header, and every message template registered before sending. That is the same template-approval
friction as WhatsApp for a channel with worse reach and no free reply window. If you are going to pay
the template-approval tax once, pay it on WhatsApp.

**WhatsApp marketing broadcast.** About 115x the cost of email for an audience you already reach by
email, in the category most likely to get your account restricted. The WhatsApp Business Messaging
Policy states verbatim: "Don't use WhatsApp for telemedicine or to send or request any health related
information, if applicable regulations prohibit distribution of such information to systems that do not
meet heightened requirements", and its prohibited list includes "Medical and healthcare products". A
GI-cancer patient advocacy charity is in the single most policy-sensitive category on the platform.
Utility and inbound only. See B21.

**Geolocation from IP.** Cheap, since the IPs are already stored, and precisely why it should not be
built. A map of cancer patients by city is a liability, not a feature. B15 deletes the IPs.

**Read time and skim-versus-read instrumentation.** Defeated by MPP for most of the audience, and
instrumenting how long a cancer patient stared at a message is indefensible for this organisation
regardless of what the law permits.

## Things they already have that a vendor will try to sell them

Suppression list mirroring from SES, complaint handling, RFC 8058 one-click unsubscribe, bot-filtered
opens, MPP classification, a pre-send safety gate with SES-calibrated thresholds, live SPF/DKIM/DMARC/MX
health checks, per-recipient send logging that survives campaign deletion, an A/B report that refuses to
name a winner it cannot support, and a `PRIVACY_UNKNOWN` segment that refuses to report a number it
cannot defend. Several of these are better reasoned than the hosted equivalents.

---

# 4. THE ONE BIG DECISION

## Keep the shared mailbox on the mail server. Never build a shared view over per-person mailboxes.

### The three options

**A. One shared mailbox, one Stalwart account, shared password.** What exists today, verified in the
source comments: `models/MailboxSettings.java:34` reasons about "whichever of the three people who share
support@ opened"; `push/PushSubscriptionRecord.java:33` and `push/NotificationRules.java:37` both say
"these are shared mailboxes, so a notification for support@ goes to every device";
`mail/MailSession.java` records measured account ids for `hr`, `support`, `partnership`, `priyanka`.
Zero build cost. Costs already being paid: no per-person attribution at the mail server, no way to revoke
one person without a password change, and `MailboxAccess.close()` logs everyone out of that mailbox (its
own comment admits this and defends it).

**B. A Stalwart group principal with per-person credentials.** `hr@` becomes a group, four humans become
members, each authenticates as themselves. Stalwart's group docs state that groups can receive email,
that group accounts cannot log in over IMAP/POP3/JMAP, and that a member gains access to the group's
inbox as a shared folder. Discussion #2969 confirms `Identity/set` used to reject group addresses and was
fixed in 0.16, so send-as over JMAP works on 0.16.19. Real per-person auth and revocation by removing a
member. Costs: the shared folder appears in every member's client and **cannot be suppressed**
(maintainer, discussion #3001: "Unfortunately that can't be disabled"); two open bugs (#2358, group inbox
double-nested and unmovable; #251, subscribing a group mailbox does not set `is_subscribed`, so Evolution
forgets it and Roundcube will not list it); and, in this codebase, it breaks `MailboxAccess`'s central
invariant. That class exists so a session proves one mailbox's password and can act on nothing else.
Option B replaces it with "one identity, many mailboxes", which is a rewrite of the security-critical
piece, not an addition to it.

**C. Per-person mailboxes with a shared view assembled in Postgres.** Poll N mailboxes, de-duplicate,
re-derive one conversation from fragments.

### The decision

**Keep A now. Plan B. Never build C.**

C is the expensive mistake, and here is why in three lines. It multiplies JMAP round trips by headcount
on a 1.8GB box that is already running Postgres, Stalwart and a Spring Boot jar. It re-solves
de-duplication and threading that the mail server already solved. And it **has no answer at all for what
address a reply goes out from**: `hr@jarurat.care` receives the mail, so a reply from
`priyanka@jarurat.care` breaks the thread for the hospital on the other end, and a reply forged as `hr@`
from a personal account is exactly the unaligned stream that DMARC enforcement (section 5, R15) will junk.

### What makes this cheap rather than expensive to get wrong

**The shared-mailbox question and the shared-view question are separable, and only one of them is
expensive to reverse.** Comments, assignment and status are per (mailbox, conversation) facts that live
in Postgres under A, under B, and under any future arrangement. Neither A nor B touches that table. So B8
can be built today without settling A-versus-B at all.

**The one genuinely irreversible choice is the key.** Get this right on day one:

- **Key on the mailbox address** (`hr@jarurat.care`). A stable string that survives A, B, migration and
  re-import.
- **Never on JMAP `accountId`.** It is opaque, per-account, and under option B the account changes
  identity entirely. `MailFolder`'s own comment already warns that JMAP ids are per-account and opaque.
- **Never on the console `app_user`.** That is the other identity system, and `MailboxAccess`'s comments
  record what happened last time the two were conflated: an authentication bypass.
- **Store `threadId` AND the RFC `Message-ID` of the conversation's first message.** A note keyed on
  `threadId` alone reattaches to the wrong conversation the day the account changes.

### The 20-minute test that settles B, to run before writing a line of it

Unverified and it matters. It could not be established whether Stalwart exposes a group inbox as a second
entry in the JMAP session `accounts` map (`isPersonal: false`) or as extra `Mailbox` rows under a
`Shared Folders` parent inside the member's own account. Stalwart's own wording points at the second.

On the box, create a throwaway group with an address, add two members, then as a member:

```
curl -k -u member@jarurat.care:PASS https://localhost/jmap/session
```

- **Two accounts, one with `isPersonal: false`:** the multi-account path is real. `JmapClient.java:297`
  currently reads only `primaryAccounts[urn:ietf:params:jmap:mail]` and discards the `accounts` map
  entirely; that one line is where option B has to begin. Medium change, and `MailboxAccess` must be
  rethought.
- **One account with extra mailboxes under a `Shared Folders` node:** much smaller. `MailFolder` already
  carries `parentId`; the folder list just gets longer and `MailboxAccess` survives untouched.

### And the build-versus-buy half of the same decision

Front Professional is $65/seat/month billed annually, so fifteen seats is roughly **$11,700/year**.
Missive Productive is $24/user/month billed yearly, roughly **$4,300/year** for fifteen; its $14 Starter
plan caps at five people, so it does not fit. Help Scout is $25/$45/$75 per user per month annually, with
a nonprofit discount typically 10 to 20 percent. Weigh those against about four days of work on a
codebase they already own, on a box they already pay for.

**But price is not the argument.** None of Front, Missive, Help Scout, Hiver or Zendesk can be pointed at
their own Stalwart server. Buying any of them moves Indian cancer patients' correspondence about their
diagnoses onto a US vendor's storage. Under DPDP that is a cross-border transfer whose Rule 7 breach
surface the charity does not control and cannot audit, on data where **list membership is itself the
diagnosis** (section 5). That is not a line item. It is the strongest argument for building the small
version rather than buying the large one.

"Just use a Google Group" does not cover it either: Google Collaborative Inbox has no structured internal
notes and no collision detection, which are precisely the two things worth building.

---

# 5. RISKS

## The frame: list membership is the diagnosis

A list named "GI cancer patients - Delhi" is not a list of email addresses with a health attribute
attached. **The membership is the health record.** There is no field to redact: `email + listId` is a
disclosure that a named person has or had gastrointestinal cancer, and in many Indian families that is
information the patient has not shared with their employer, their in-laws, or their insurer. The same
applies to a caregiver list, which discloses a diagnosis in the household.

One legal nuance that raises the bar for the next nine months. **The DPDP Act has no "sensitive personal
data" category** and health data gets no special tier. But the IT Act section 43A and the SPDI Rules 2011
remain in force until 13 May 2027, and those **do** define a sensitive category expressly including
"medical records and history" and "physical, physiological and mental health condition", with heightened
security obligations. Both regimes run concurrently until then, and the prudent reading is to apply the
higher standard.

**None of this is legal advice.** Before 14 November 2026, the consent notice wording and the privacy
page are worth an hour of an Indian data protection lawyer's time, and that hour is better spent than a
month of extra engineering.

## Ranked risks

**R1. Five bulk CSV export routes write no audit row, four are reachable by VIEWER, and there is no
compensating request log.** An insider or a stolen session can take the entire patient list and leave zero
trace anywhere in the system. Verified. See B1. Enforceable under Rule 6 from 14 November 2026.
**Fix in Phase 2, one day.**

**R2. `deploy.sh:31` writes `sudo -u postgres pg_dump jarurat_mailer | gzip` to
`/home/ubuntu/backup_pre_journey_<stamp>.sql.gz`.** Verified. The complete patient list, in plaintext, in
a home directory, on every deploy, with no rotation and no deletion. Move it to a mode-0600 file in a
dedicated directory, `age` or `gpg` encrypt it, and delete dumps older than 14 days. **Ten lines. Fix in
Phase 0.** Also check whether the EBS volume is encrypted: that is the one control that partly mitigates
it, it costs nothing, and it was not verified.

**R3. The audit log is itself a patient roster, with unbounded retention.** `AuditLog` stores email
addresses as `target`: `audit.record("SUBSCRIBER_ADDED", s.getEmail(), ...)`,
`audit.record("SUBSCRIBER_DELETED", s.getEmail(), null)`, `audit.record("SUPPRESSED", email, "manual")`.
Anyone with `AUDIT_READ` can reconstruct the list, and `SUBSCRIBER_DELETED` rows make it a durable record
of people who exercised erasure. Store a subscriber id, resolvable only on a separately permissioned
lookup. Set a one-year retention. **Part of B1.**

**R4. Erasure is a false report.** `SubscriberService.delete()` is two statements and leaves `email` and
`name` in `campaign_recipient`, `email` in `click_event`, and `email`, `ip` and `userAgent` in
`tracking_event`, all fully searchable. **B6.**

**R5. `tracking_event` holds IP addresses and user agents of cancer patients forever, on a 1.8GB box,
while the table holding subject lines purges at 60 days.** **B15, half a day.**

**R6. There is no consent record of any kind, and every row is `SUBSCRIBED` by field initialiser.**
Verified. Nothing was carried over from the Zoho migration. **B5.** The section 5(2) legacy notice closes
the historic half for the cost of one campaign.

**R7. Rule 7 breach notification has no materiality threshold.** Any confirmed breach means preliminary
intimation to the Board without delay, a detailed report within 72 hours, and individual notification to
every affected data principal. Penalty ceilings are Rs 250 crore for the security-safeguards failure and
Rs 200 crore for the notification failure. **For a patient advocacy charity whose entire operating asset
is that patients trust it enough to be on the list, that notification is an extinction-level event
regardless of the fine. The fine is the second-worst outcome.**

**R8. Section 9(3) is an absolute prohibition on behavioural tracking of under-18s, and JCF cannot
currently tell whether it is doing it.** No date of birth, age or child flag exists anywhere in the
schema, while `OpenTrackingService` records IP, user agent and timing for every recipient without
exception, and `EngagementSegment` turns that into cohorts. **B16, thirty lines.**

**R9. Campaign mail has zero delivery data, and the safety gate presents an estimate as a measurement.**
`aws.ses.configurationSet` is blank. `SafetyCheckService` computes over a fixed 30-day window with a
100-message floor; AWS computes over an unpublished "representative volume" and counts only hard bounces.
The two rates **will disagree**, and the screen presents the app's number as if it were the one AWS acts
on. **F1, half a day, no code.**

**R10. `UI-SPEC.md` instructs the next contributor to delete three shipped features and tighten a CSP
that 166 inline handlers depend on.** The spec's own opening rule makes this authoritative. **That is a
trap with the safety off. F9, two hours.**

**R11. Deployment is unverified in three ways.** The local jar predates HEAD by three commits;
`deploy.sh` still checks for `journey%` and `otp%` tables and knows nothing about `queued_message`,
`push_subscription`, `notification_rules`, `device_token` or `mailbox_settings`; and with
`ddl-auto=update` and no Flyway, a failed table creation is a log line nobody reads. **F9.**

**R12. The two places where a defect is unrecoverable have no tests.** `campaignsplus` (CSV import writes
the subscriber base) and `sns` (a signature check on a public unauthenticated endpoint). **B24.**

**R13. Device revocation is dead, so a lost phone holds a mailbox for 180 days**, and the only recovery
signs out everyone sharing that mailbox. **F3, half a day.**

**R14. Every rate on the analytics screen is statistically illegible at this list size, and the product
already knows it.** At 1,000 delivered and a 2.4% click rate you have 24 clickers and a 95% interval of
roughly plus or minus 0.95 points. `JourneyService.variantReport` reasons about exactly this and refuses
to call a winner under 300 per arm, with the comment that "a tool that names a winner on six opens
teaches people to trust a number that has not earned it". That reasoning is correct and it is quarantined
in the A/B screen. **B13.**

**R15. Moving DMARC to `p=quarantine` before the `rua` is fixed silently junks streams nobody has
enumerated.** The known streams are safe: SES campaign and transactional mail aligns on both identifiers
(a custom MAIL FROM at `ses.jarurat.care` relaxed-aligns with `jarurat.care`, and SES Easy DKIM signs
`d=jarurat.care`), and Stalwart's relayed webmail is Easy-DKIM-signed by SES. The streams that will bite
are: anything the Vercel-hosted site sends as `@jarurat.care`; forms, events or donation platforms
configured with a jarurat.care From:; leftover Zoho paths, since `migrate-zoho.py` is in the repo and
that history is recent; staff using a client pointed at a non-SES relay; and **mailing lists**, because
forwarding breaks SPF but an aligned DKIM signature usually survives it, while mailing lists break both,
so posts to oncology or NGO lists from a jarurat.care address would vanish for subscribers at enforcing
providers.

**Sequence: F2 (fix `rua`, drop `ruf`, drop `sp=none`) now. B22 (report reader) after 60 days.
`p=quarantine; t=y` for a fortnight only after 60 to 90 days of reports show a stable, fully aligned
source set. Then drop `t=y`. Stop at quarantine; reject buys almost nothing and raises the cost of every
mistake.** Note that DMARCbis (RFC 9989, 9990, 9991) removes the `pct` tag, so the classic
"pct=10, 25, 50" ramp is retired and `t` is binary.

**The case for moving off `p=none` at all is not compliance.** Google, Yahoo and Microsoft all explicitly
accept `p=none`; anyone saying Gmail "requires" enforcement is repeating vendor marketing. The case is
that a spoofed "Jarurat Care Foundation" message asking a patient for money, bank details or medical
records is a specific, foreseeable harm, and `p=none` tells every receiver in the world to take no action
on it.

**R16. Unverified items that would change the picture and cost minutes to check on the box.** Whether the
EBS volume is encrypted; whether campaign footers carry JCF's physical postal address (needed for
CAN-SPAM where it applies); whether nginx access logs are retained; and whether `jmap.webPushKey` on
Stalwart matches `PUSH_VAPID_PRIVATE_KEY` in the app. Check all four in Phase 0.

---

# 6. PHASED SEQUENCE

Each phase is independently shippable and useful on its own. Do not start a phase before the previous one
is deployed.

## Phase 0. This week. Configuration and DNS only. No code.

| Item | Where |
|---|---|
| SES configuration set + SNS topic + env vars | F1 |
| `rua` to `dmarc@jarurat.care`, drop `ruf`, drop `sp=none` | F2 |
| Encrypt and rotate the `pg_dump` in `deploy.sh` | R2 |
| Confirm the EBS volume is encrypted | R2, R16 |
| Register the domain in Google Postmaster Tools (one TXT record) | B14 |
| Turn on SES Virtual Deliverability Manager for one month | below |
| Check the four unverified items on the box | R16 |
| Deploy HEAD (the jar on prod is three commits behind) | R11 |

**Run the VDM experiment before committing a week to B2 and B10.** SES Virtual Deliverability Manager is
$0.07 per 1,000 emails for the first 10M a month. At 20,000 messages a month that is **$1.40**. It gives
per-ISP delivery data at account, ISP, identity and configuration-set level. Turn it on for one month and
see whether it says anything the message log will not. Cheapest experiment in this document by a wide
margin.

**Ships:** campaign mail starts producing delivery data for the first time, and every DMARC decision
after this is made on a real sample.

## Phase 1. Weeks 1 to 2. Finish what is already built.

F3 devices contract, F4 push proof plus diagnostic, F5 outbox wiring, F6 settings honesty, F7 two
buttons, F8 inbound filter decision, F9 spec and deploy.sh corrections, F10 dead surface, F11 pre-flight
safety panel.

**Ships:** a mail client whose Settings screen does not lie, working device revocation, undo-send, and a
design contract that no longer instructs the next hire to break the product. About four days of work
across two weeks.

## Phase 2. Weeks 3 to 6. The 14 November deadline.

B1 export auditing and audit de-identification. B5 consent record, import consent capture, public signup
form with per-list double opt-in. B6 real erasure plus single-subject export. B15 retention jobs. B16
minors flag. Then **send the section 5(2) legacy notice campaign**, which needs a privacy page and no
code.

**Ships:** JCF can detect a bulk export, prove consent, honour an erasure request truthfully, and has
discharged its legacy-consent obligation. This phase must land before 14 November 2026. Everything in it
is also the right thing to do independent of the date.

## Phase 3. Weeks 7 to 9. Measurement honesty.

B3 delete the contradicting metrics (do this first; it is subtraction and it makes everything after it
legible). B2 recipient-domain panel. B10 DeliveryDelay, Reject and the dropped bounce fields. B13
interval open rates, confidence bounds, the MDE fix, and the Active audience headline. B14 the four small
deliverability items. B24 first tests for `campaignsplus` and `sns`.

**Ships:** one open rate instead of three, the first per-provider view the organisation has ever had, and
the earliest possible warning when a provider turns against them.

## Phase 4. Weeks 10 to 14. The charity-specific product.

B4 preference centre with topics, frequency and pause. B9 frequency cap plus DECEASED and
DO_NOT_CONTACT. B7 contact timeline with note and hold-until date (the hold column is shared with B4's
`pausedUntil`; build it once).

**Ships:** the thing that makes this a patient advocacy tool rather than a small Mailchimp. A patient
mid-treatment can pause instead of leaving, a caseworker can see one family's whole history before
picking up the phone, and nobody gets four emails in a week because they are on four lists.

## Phase 5. Weeks 15 to 17. Coordination and the board.

B8 shared inbox comments, assignment, status and the collision heartbeat. B23 canned responses. B11 board
and donor report (depends on B2 from Phase 3 for the hospital-domain line, and adds the `purpose` enum to
`Campaign`).

**Run the 20-minute Stalwart group test (section 4) during this phase**, before any decision about option
B. B8 does not depend on the answer.

**Ships:** two people stop replying to the same family, "do not send the standard follow-up" has a place
to live, and the charity has a one-page document its board can read.

## Phase 6. Weeks 18 onward. Only what someone asks for.

In this order if asked: B12 reply capture (highest value of the remainder, and it retires the dead INBOUND
filter). B18 cross-campaign rule segments. B17 broadcast subject A/B. B22 DMARC report reader, and the
`p=quarantine` decision it gates. B19 custom fields plus date and inactivity sources, as one unit. B20
template library, then measure for six months before deciding about the block composer. B21 WhatsApp,
utility and inbound only.

**Do not start Phase 6 speculatively.** Every item in it is real work that solves a problem nobody in the
organisation has yet said out loud. Phases 0 to 5 are about six weeks of actual engineering spread across
four months, and they leave the product finished rather than expanded.

---

## Appendix: where the seven investigations disagreed, and what was decided

| Disagreement | Decision |
|---|---|
| Is the message log's verbatim SMTP reply available for campaigns? | **No.** `StalwartDeliveryLog` reads Stalwart's local log; campaigns go to the SES API. Verbatim replies exist for webmail and SMTP submission only. F1 is what changes this. |
| Does reverse DNS matter? | **Not today.** The note that "mail sent directly from the box will be junked" is right, but it describes a path that does not exist while the relay route holds. Build the alarm (B14), not the DNS request. |
| Delete `ClickEvent`, or purge it for compliance? | **Delete the table** (B3.2). That resolves the compliance concern and removes an unfiltered click table that renders identically to the filtered one. |
| `tracking_event`: null the IP at 90 days, or purge rows at 12 to 18 months? | **Both** (B15). Null the IP at 90 days so `reclassify` keeps working; purge rows at 18 months. |
| Is A/B testing a gap? | **No.** It exists and is more honest than Mailchimp's. The gap is that it is trapped inside a journey SPLIT node (B17), plus a 6% optimism in the MDE formula (B13). |
| Build a drag-and-drop builder? | **No.** The GrapesJS measurement is decisive: about 307 KB gzipped, requires `unsafe-eval`, and needs Node for MJML. Templates first, block composer only if templates prove insufficient (B20). |
| Shared mailbox architecture? | **Keep the shared mailbox on the mail server. Never build a shared view over per-person mailboxes.** Section 4. |
| Is the consent gap or the export gap the bigger risk? | **The export gap.** Missing consent is a paperwork failure with a paperwork remedy (section 5(2), one campaign). The unaudited export is the breach, it is undetectable, and it costs a day to fix versus weeks for consent infrastructure. Both are in Phase 2; B1 goes first. |
