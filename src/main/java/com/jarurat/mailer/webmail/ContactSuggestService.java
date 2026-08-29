package com.jarurat.mailer.webmail;

import com.jarurat.mailer.mail.Identity;
import com.jarurat.mailer.mail.MailAddress;
import com.jarurat.mailer.mail.MailFolder;
import com.jarurat.mailer.mail.MailService;
import com.jarurat.mailer.mail.MessagePage;
import com.jarurat.mailer.mail.MessageSummary;
import com.jarurat.mailer.services.SesSender;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * The address book behind the compose box, built out of the mailbox's own mail.
 *
 * Stalwart holds no per-user contact list this app can read with a mailbox
 * password, so there is nothing to look anything up in. What there is, is the mail
 * itself: the people this mailbox has written to are in Sent and the people who
 * have written to it are in Inbox, and between them they are a better address book
 * than a hand-maintained one would ever be, because they are exactly the addresses
 * that have actually been used. So this class reads those two folders, tallies who
 * appeared and when, and keeps the answer.
 *
 * Three things shape every decision below.
 *
 * The first is that a keystroke may not cost a round trip. A harvest is four JMAP
 * requests, which is fine once a minute and absurd once a letter, so the harvest
 * runs on its own thread and every request is served out of a per-mailbox cache.
 *
 * The second is that a cold cache may not stall the compose box. A request that
 * finds nothing cached starts a harvest and then waits for it for a fixed few
 * hundred milliseconds and no longer; if the mail server is slower than that, the
 * request answers with an empty list and the harvest carries on filling the cache
 * for the next keystroke. Nobody ever waits on the mail server while typing.
 *
 * The third is that this is not allowed to fail loudly. Autocomplete is a
 * convenience sitting on top of a compose box that has to keep working, so every
 * failure in here, transport, auth, parse or otherwise, degrades to an empty list.
 * The failure is cached too, for the same TTL, so a mail server that is down is
 * asked once a minute rather than once a letter.
 *
 * What is deliberately NOT a source: the subscriber base. Campaign Studio holds
 * the marketing contacts in Subscriber and ListMember, and they would make a much
 * longer autocomplete list than a mailbox's own correspondents. They are also
 * walled off from this side of the product on purpose. An HR person signed in to
 * mail holds MAIL_READ and no SUBSCRIBERS_READ, and the point of that separation
 * is that the marketing audience is not theirs to browse. Feeding it in here would
 * hand them the entire base one prefix at a time, through an endpoint that never
 * checks the permission guarding it everywhere else. The wall stays up: nothing in
 * this class reads a repository.
 */
@Service
public class ContactSuggestService {

    /** One suggestion. Exactly the fields the client contract promises, and nothing else. */
    public record Contact(String email, String name, Instant lastSeen) {}

    /**
     * Where an address came from, and what that is worth.
     *
     * An address in Sent is one this mailbox chose to write to, which is far stronger
     * evidence of wanting to write to it again than an address in Inbox, which
     * includes every stranger and every newsletter. The directory weight is a floor
     * rather than a rank: it is there so an organisation address the user has never
     * corresponded with can still be offered, but it must never outrank somebody they
     * genuinely have.
     */
    private enum Source {
        SENT(3.0), INBOX(1.0), DIRECTORY(0.01);

        private final double weight;

        Source(double weight) {
            this.weight = weight;
        }
    }

    /**
     * How fast a contact goes cold, in days. Recency and frequency are not two
     * separate scores added together here; one exponential decay does both, because a
     * sum of decayed sightings rises with how often an address appears and falls with
     * how long ago it appeared, which is the ranking actually wanted.
     */
    private static final double HALF_LIFE_DAYS = 30.0;

    /**
     * The share of a sighting's weight that never decays. Without it a years-old
     * correspondent scores indistinguishably from zero and frequency stops breaking
     * ties among old contacts, so twenty ancient messages would rank level with one.
     */
    private static final double UNDECAYING_SHARE = 0.05;

    /**
     * How long a snapshot is kept once nothing is asking for it.
     *
     * This is not a correctness guard, because a snapshot can only be served to a
     * session that has the mailbox open and that took its password. It is a heap
     * guard: an address book for a mailbox nobody has touched in half an hour has no
     * business still sitting in memory on a two vCPU box.
     */
    private static final long RETAIN_MILLIS = 30L * 60_000L;

    /** MailService clamps to jarurat.mail.max-page-size, so asking for more than this wastes nothing. */
    private static final int PAGE_SIZE = 100;

    /**
     * Automated senders, filtered out of the Inbox side only.
     *
     * A mailbox that gets a nightly report from noreply@ has that address at the very
     * top of the frequency tally, and it is the one address in the whole list that
     * nobody can usefully write to. It is filtered on the Inbox side and never on the
     * Sent side: if somebody has actually sent mail to an address shaped like this,
     * they knew something this heuristic does not and it is not going to hide it.
     */
    private static final Pattern AUTOMATED = Pattern.compile(
            "^(no-?reply|do-?not-?reply|donotreply|bounces?|mailer-daemon|postmaster|notifications?)([.+-].*)?$");

    /** The marks NFD leaves behind, which is how an accent gets folded away. */
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private final MailService mail;
    private final long ttlMillis;
    private final long deadlineMillis;
    private final int scanPerFolder;
    private final int maxMailboxes;

    /** One snapshot per mailbox, bounded by maxMailboxes. Keyed on the lowercase address. */
    private final Map<String, Snapshot> cache = new ConcurrentHashMap<>();

    /**
     * The harvest currently running for a mailbox, so a burst of keystrokes against a
     * cold cache starts one harvest and not one per letter. Cleared when it finishes.
     */
    private final Map<String, CompletableFuture<List<Ranked>>> inFlight = new ConcurrentHashMap<>();

    /**
     * Harvests run here rather than on the request thread, which is the whole reason
     * the deadline below can be honoured: a request can walk away from a slow harvest
     * and the harvest still finishes and still fills the cache for the next one.
     */
    private final ExecutorService harvester = Executors.newVirtualThreadPerTaskExecutor();

    public ContactSuggestService(MailService mail,
                                 @Value("${jarurat.mail.contacts.ttl-seconds:60}") int ttlSeconds,
                                 @Value("${jarurat.mail.contacts.deadline-ms:250}") int deadlineMs,
                                 @Value("${jarurat.mail.contacts.scan-per-folder:200}") int scanPerFolder,
                                 @Value("${jarurat.mail.contacts.max-mailboxes:32}") int maxMailboxes) {
        this.mail = mail;
        this.ttlMillis = Math.max(0L, ttlSeconds) * 1000L;
        this.deadlineMillis = Math.max(1, deadlineMs);
        this.scanPerFolder = Math.max(1, scanPerFolder);
        this.maxMailboxes = Math.max(1, maxMailboxes);
    }

    /**
     * Virtual threads cost nothing to abandon, so the pool is dropped rather than
     * drained. close() on this executor waits for every running task, and a task in
     * here can be parked on a JMAP call with the whole twenty second request timeout
     * still to run, which would make shutdown wait twenty seconds for work whose only
     * consumer is a cache that is about to be garbage.
     */
    @PreDestroy
    void stop() {
        harvester.shutdownNow();
    }

    // ------------------------------------------------------------------ the one entry point

    /**
     * The ranked suggestions for one mailbox and one typed prefix.
     *
     * Never throws, and never blocks for longer than the deadline. An empty list means
     * "nothing to offer right now", which covers no match, a cold cache and a mail
     * server that is refusing to talk, and the caller is not meant to tell those
     * apart: all three should produce the same silent dropdown.
     */
    public List<Contact> suggest(String mailbox, String query, int limit) {
        if (mailbox == null || mailbox.isBlank() || limit <= 0) return List.of();
        try {
            String wanted = fold(query);
            List<Ranked> all = snapshot(key(mailbox));

            List<Contact> out = new ArrayList<>(Math.min(limit, all.size()));
            // The snapshot is already sorted by rank, so the first matches found are the
            // best ones and the scan can stop as soon as the page is full.
            for (Ranked candidate : all) {
                if (!candidate.matches(wanted)) continue;
                out.add(candidate.contact());
                if (out.size() >= limit) break;
            }
            return List.copyOf(out);
        } catch (RuntimeException e) {
            // Deliberately unconditional. Nothing in here is worth breaking a compose box
            // over, and the endpoint's contract is that it always answers with a list.
            return List.of();
        }
    }

    // ------------------------------------------------------------------ cache

    private record Snapshot(List<Ranked> entries, long builtAt) {}

    /**
     * The current snapshot for a mailbox, refreshing it behind the answer if it has
     * gone stale.
     *
     * A stale snapshot is served rather than discarded, and that is the point of
     * separating the TTL from the retention window: last minute's contacts are not
     * wrong, they are only missing this minute's, so the right thing to do with a
     * stale entry is hand it over and refresh behind it. Only a mailbox with nothing
     * usable cached makes anybody wait, and then only for the deadline.
     */
    private List<Ranked> snapshot(String mailbox) {
        long now = System.currentTimeMillis();
        Snapshot cached = cache.get(mailbox);
        if (cached != null && now - cached.builtAt() < RETAIN_MILLIS) {
            if (now - cached.builtAt() >= ttlMillis) refresh(mailbox);
            return cached.entries();
        }

        try {
            return refresh(mailbox).get(deadlineMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // The harvest is still running and will still fill the cache. Answering
            // empty now and correctly on the next keystroke beats holding the box.
            return List.of();
        } catch (ExecutionException e) {
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    /**
     * Starts a harvest for a mailbox, or joins the one already running for it.
     *
     * Not computeIfAbsent, for the reason written out at length on JmapClient.session:
     * that method runs its mapping function inside the map's bin lock, and on Java 21 a
     * virtual thread that blocks inside a synchronized block pins its carrier. This one
     * only submits work rather than doing it, so the exposure is much smaller, but the
     * putIfAbsent shape costs nothing and does not have to be reasoned about at all.
     */
    private CompletableFuture<List<Ranked>> refresh(String mailbox) {
        CompletableFuture<List<Ranked>> running = inFlight.get(mailbox);
        if (running != null) return running;

        CompletableFuture<List<Ranked>> started = new CompletableFuture<>();
        CompletableFuture<List<Ranked>> raced = inFlight.putIfAbsent(mailbox, started);
        if (raced != null) return raced;

        try {
            harvester.execute(() -> {
                List<Ranked> built;
                try {
                    built = build(mailbox);
                } catch (RuntimeException e) {
                    built = List.of();
                }
                // An empty result is stored like any other, so a mail server that is down
                // is asked again after the TTL rather than on the very next keystroke.
                store(mailbox, built);
                inFlight.remove(mailbox, started);
                // Completed normally even when the harvest failed, so a caller waiting on
                // the deadline never has to unwrap an exception to find an empty list.
                started.complete(built);
            });
        } catch (RejectedExecutionException e) {
            // Only reachable during shutdown, once stop() has emptied the pool.
            inFlight.remove(mailbox, started);
            started.complete(List.of());
        }
        return started;
    }

    /**
     * Writes a snapshot and keeps the cache inside its bound.
     *
     * The bound matters more than the size of it. These are shared mailboxes opened by
     * hand, so in practice a handful are ever live at once, but the key is an address
     * that arrived on a session and an unbounded map keyed on anything user-influenced
     * is a slow leak waiting for the day something starts opening mailboxes in a loop.
     * The oldest snapshots go first, which is as close to least-recently-useful as this
     * gets without tracking reads.
     */
    private void store(String mailbox, List<Ranked> entries) {
        cache.put(mailbox, new Snapshot(entries, System.currentTimeMillis()));
        if (cache.size() <= maxMailboxes) return;

        List<Map.Entry<String, Snapshot>> byAge = new ArrayList<>(cache.entrySet());
        byAge.sort(Comparator.comparingLong(e -> e.getValue().builtAt()));
        // One pass over a snapshot of the entries, so two threads evicting at the same
        // time cannot spin against each other.
        for (int i = 0; i < byAge.size() - maxMailboxes; i++) {
            cache.remove(byAge.get(i).getKey());
        }
    }

    /** Only so a test can assert the bound actually holds. Nothing in the app reads this. */
    int cachedMailboxes() {
        return cache.size();
    }

    // ------------------------------------------------------------------ harvest

    /** One address as it appeared once, before anything has been tallied. */
    private record Seen(String email, String name, Instant when, Source source) {}

    /**
     * Reads the mailbox and turns it into a ranked list.
     *
     * Runs on the harvester and never on a request thread. The three reads are
     * genuinely independent round trips, so they go out together for the same reason
     * MailService.listFoldersForAll fans out: waiting for them one after another adds
     * three request times up for nothing. Each one is guarded on its own, so a folder
     * this mailbox does not have, or a source Stalwart refuses, costs its own
     * contribution rather than the whole address book.
     */
    private List<Ranked> build(String mailbox) {
        String sentId = null;
        String inboxId = null;
        try {
            // One Mailbox/get for both roles. Two folderByRole calls would fetch the
            // whole folder list twice for two ids that arrive in the same answer.
            for (MailFolder folder : mail.listFolders(mailbox)) {
                if (folder.isRole("sent")) sentId = folder.id();
                if (folder.isRole("inbox")) inboxId = folder.id();
            }
        } catch (RuntimeException e) {
            // No folder list means no mail sources, but the directory below may still
            // answer, so this is not the end of the harvest.
            sentId = null;
            inboxId = null;
        }

        Queue<Seen> seen = new ConcurrentLinkedQueue<>();
        String sent = sentId;
        String inbox = inboxId;
        try (ExecutorService fan = Executors.newVirtualThreadPerTaskExecutor()) {
            if (sent != null) fan.execute(() -> scanFolder(mailbox, sent, Source.SENT, seen));
            if (inbox != null) fan.execute(() -> scanFolder(mailbox, inbox, Source.INBOX, seen));
            fan.execute(() -> scanDirectory(mailbox, seen));
        }

        return rank(key(mailbox), seen);
    }

    /**
     * Walks one folder newest first and records who was on each message.
     *
     * Sent is read for its recipients and everything else for its senders, which is the
     * same asymmetry MessageSummary.counterparty exists for. Paging stops at the scan
     * cap or when the folder runs out, whichever comes first, and it trusts
     * MessagePage.hasMore rather than treating a short page as the end, because
     * MailService clamps the page size to a configurable maximum that an operator may
     * have set lower than the size asked for here.
     */
    private void scanFolder(String mailbox, String folderId, Source source, Queue<Seen> sink) {
        try {
            int offset = 0;
            while (offset < scanPerFolder) {
                MessagePage page = mail.listMessages(mailbox, folderId, offset,
                        Math.min(PAGE_SIZE, scanPerFolder - offset));
                List<MessageSummary> rows = page.messages();
                if (rows == null || rows.isEmpty()) return;

                for (MessageSummary row : rows) {
                    Instant when = row.receivedAt();
                    if (source == Source.SENT) {
                        record(sink, row.to(), when, source);
                        record(sink, row.cc(), when, source);
                    } else {
                        record(sink, row.from(), when, source);
                    }
                }

                if (!page.hasMore()) return;
                offset = page.nextOffset();
            }
        } catch (RuntimeException e) {
            // Whatever this folder gave up before it failed is still worth keeping.
        }
    }

    /**
     * The only directory reachable without an admin token: the addresses Stalwart says
     * this mailbox may send as.
     *
     * It is a thin directory and worth being honest about. Identity/get returns the
     * mailbox's own address plus any alias or shared address it is entitled to send
     * from, so it names some of the organisation's own mailboxes but by no means all
     * of them. The full list lives behind StalwartAdminService, which authenticates as
     * the server administrator, and reaching for that from an endpoint every MAIL_READ
     * session can call once per keystroke would put an admin credential behind a
     * keystroke. That is not a trade worth making for a dropdown.
     *
     * MailCredentialStore.knownUsers was the other candidate and is deliberately not
     * used. It is not a directory: it is the set of mailboxes somebody has unlocked in
     * this process since the last restart, so it would make one colleague's sign-in
     * visible in another colleague's compose box and would answer differently after
     * every deploy.
     */
    private void scanDirectory(String mailbox, Queue<Seen> sink) {
        try {
            for (Identity identity : mail.listIdentities(mailbox)) {
                MailAddress address = identity.asAddress();
                sink.add(new Seen(address.email(), address.name(), null, Source.DIRECTORY));
            }
        } catch (RuntimeException e) {
            // An account with no send identity is unusual but is not a reason to lose the
            // correspondents the two folder scans just found.
        }
    }

    private static void record(Queue<Seen> sink, List<MailAddress> addresses, Instant when, Source source) {
        if (addresses == null) return;
        for (MailAddress address : addresses) {
            if (address == null) continue;
            sink.add(new Seen(address.email(), address.name(), when, source));
        }
    }

    // ------------------------------------------------------------------ ranking

    /** Everything known about one address while the tally is being built. */
    private static final class Tally {
        private String name = "";
        private Instant namedAt;
        private Instant lastSeen;
        private double score;
    }

    /**
     * Collapses the raw sightings into one scored row per address, best first.
     *
     * The score is a sum over sightings of the source's weight, decayed by how long ago
     * the sighting was. That is one expression doing the work of both halves of
     * "recency and frequency": more sightings raise it, older sightings raise it less.
     */
    private List<Ranked> rank(String mailbox, Queue<Seen> seen) {
        long now = System.currentTimeMillis();
        Map<String, Tally> byAddress = new LinkedHashMap<>();

        for (Seen sighting : seen) {
            String email = normalise(sighting.email());
            if (email == null) continue;
            // Nobody needs their own address offered back to them, and the directory
            // source would otherwise suggest the From address as a To address.
            if (email.equals(mailbox)) continue;
            if (sighting.source() == Source.INBOX && isAutomated(email)) continue;

            Tally tally = byAddress.computeIfAbsent(email, k -> new Tally());
            tally.score += sighting.source().weight * weightOf(sighting.when(), now);

            Instant when = sighting.when();
            if (when != null && (tally.lastSeen == null || when.isAfter(tally.lastSeen))) {
                tally.lastSeen = when;
            }
            // The most recent spelling of somebody's name wins, because that is the one
            // they are using now. A sighting with no date never displaces a dated one.
            String name = sighting.name() == null ? "" : sighting.name().trim();
            if (!name.isEmpty() && (tally.name.isEmpty()
                    || (when != null && (tally.namedAt == null || when.isAfter(tally.namedAt))))) {
                tally.name = name;
                tally.namedAt = when;
            }
        }

        List<Ranked> ranked = new ArrayList<>(byAddress.size());
        for (Map.Entry<String, Tally> entry : byAddress.entrySet()) {
            Tally tally = entry.getValue();
            ranked.add(Ranked.of(new Contact(entry.getKey(), tally.name, tally.lastSeen), tally.score));
        }
        // Score first, then recency, then the address itself, so two contacts that
        // genuinely tie come back in the same order on every request and the dropdown
        // does not reshuffle itself between keystrokes.
        ranked.sort(Comparator.comparingDouble(Ranked::score).reversed()
                .thenComparing(r -> r.contact().lastSeen() == null ? Instant.EPOCH : r.contact().lastSeen(),
                        Comparator.reverseOrder())
                .thenComparing(r -> r.contact().email()));
        return List.copyOf(ranked);
    }

    /** One sighting's share of its source weight, given how long ago it was. */
    private static double weightOf(Instant when, long now) {
        if (when == null) return 1.0;
        double ageDays = Math.max(0.0, (now - when.toEpochMilli()) / 86_400_000.0);
        double fresh = Math.pow(0.5, ageDays / HALF_LIFE_DAYS);
        return UNDECAYING_SHARE + (1.0 - UNDECAYING_SHARE) * fresh;
    }

    private static boolean isAutomated(String email) {
        int at = email.indexOf('@');
        return at > 0 && AUTOMATED.matcher(email.substring(0, at)).matches();
    }

    /**
     * The same shape check the campaign side and MailService.cleanAddresses use, so an
     * address that would be refused at send time is never offered at compose time.
     */
    private static String normalise(String raw) {
        if (raw == null) return null;
        String address = raw.trim().toLowerCase(Locale.ROOT);
        return SesSender.EMAIL_OK.matcher(address).matches() ? address : null;
    }

    private static String key(String mailbox) {
        return mailbox.trim().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ matching

    /**
     * A contact with its match keys already folded.
     *
     * Folding happens once here at harvest time rather than once per contact per
     * keystroke, which is the difference between a few hundred normalisations a minute
     * and a few hundred for every letter typed. Only the query is folded on the
     * request path.
     */
    private record Ranked(Contact contact, double score,
                          String email, String local, String domain, List<String> nameTokens) {

        static Ranked of(Contact contact, double score) {
            String folded = fold(contact.email());
            int at = folded.indexOf('@');
            String local = at > 0 ? folded.substring(0, at) : folded;
            String domain = at >= 0 && at < folded.length() - 1 ? folded.substring(at + 1) : "";

            List<String> tokens = new ArrayList<>();
            String name = fold(contact.name());
            if (!name.isEmpty()) {
                // The whole name first, so "priya s" matches, then each word, so "sharma"
                // matches a person whose surname is the part that got typed.
                tokens.add(name);
                for (String word : name.split("\\s+")) {
                    if (!word.isEmpty() && !word.equals(name)) tokens.add(word);
                }
            }
            return new Ranked(contact, score, folded, local, domain, List.copyOf(tokens));
        }

        /**
         * Prefix matching only, and never a substring match. A substring match on a
         * short prefix puts an unrelated address at the top of the list under the
         * person's cursor, which is how an autocomplete sends mail to the wrong person.
         */
        boolean matches(String wanted) {
            if (wanted.isEmpty()) return true;
            if (wanted.charAt(0) == '@') {
                // Typing the domain is how somebody asks for "anyone internal".
                String rest = wanted.substring(1);
                return !rest.isEmpty() && domain.startsWith(rest);
            }
            if (email.startsWith(wanted) || local.startsWith(wanted)) return true;
            for (String token : nameTokens) {
                if (token.startsWith(wanted)) return true;
            }
            return false;
        }
    }

    /**
     * Case and accent insensitive comparison form.
     *
     * NFD splits an accented letter into the letter and a combining mark, and dropping
     * the marks is what makes "jose" match "Jose" spelled with an accent and "ramirez"
     * match the same surname with one. It does not reach letters that carry no
     * separable accent, so "ss" will not match the German sharp s and "o" will not
     * match a slashed o. Those are the honest limits of doing this without a full
     * collator, and neither has come up in a jarurat.care mailbox.
     */
    static String fold(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String decomposed = Normalizer.normalize(raw.trim(), Normalizer.Form.NFD);
        return COMBINING_MARKS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT).trim();
    }
}
