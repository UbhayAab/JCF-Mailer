package com.jarurat.mailer.push;

import com.jarurat.mailer.mail.InMemoryMailCredentialStore;
import com.jarurat.mailer.mail.JmapClient;
import com.jarurat.mailer.mail.MailFolder;
import com.jarurat.mailer.mail.MailService;
import com.jarurat.mailer.mail.MailSession;
import com.jarurat.mailer.webmail.MailPollApi;
import com.jarurat.mailer.webmail.MailboxAccess;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * That the rules are actually consulted by something a person's mail goes through.
 *
 * THIS FILE EXISTS BECAUSE THE RULES ONCE HAD NO CALLERS AT ALL
 * ------------------------------------------------------------------------------
 * NotificationRulesTest is a hundred assertions about lanes and every one of them
 * passed while decide() was dead code, because a pure function does not care whether
 * anybody calls it. So the whole feature - quiet hours, VIPs, direct-to-me, the mute
 * list, the settings screen behind all of it - was green in the build and absent on
 * the phone: every message arrived as the interrupting lane, a Cc to fifty people
 * buzzed at dinner, and three in the morning was as loud as three in the afternoon.
 * That is not a bug a behaviour test finds, because there is no behaviour to test.
 * It is found by asking whether the call site exists.
 *
 * So the first test reads the compiled classes rather than the source. A call site is
 * a constant pool entry naming NotificationRules and the method, which survives
 * renames, reformatting and comments, and cannot be satisfied by a mention in a
 * javadoc or by another test calling decide() to test itself. If somebody deletes the
 * line in MailPollApi that evaluates a lane, or reverts sendFailed to building lane A
 * by hand, this fails on the next build with the name of the file that lost it.
 *
 * The rest of the file is the behaviour on the poll path, which is where the rules
 * reach a person today. The push path needs a Spring context to read a rules row and
 * is pinned in SendFailureLaneTest.
 */
class NotificationRulesWiringTest {

    /** The internal name a constant pool uses, which is the form with slashes. */
    private static final String RULES = "com/jarurat/mailer/push/NotificationRules";

    private static final String USER = "priya@jarurat.care";
    private static final String INBOX = "mb-inbox-1";

    // ==================================================================
    // The call sites themselves
    // ==================================================================

    @Test
    @DisplayName("decide() is called by production code, which is the thing that was missing")
    void decideHasAProductionCaller() throws Exception {
        Set<String> callers = callersOf("decide");

        assertThat(callers)
                .as("Nothing in src/main calls NotificationRules.decide(). That is exactly the "
                        + "state this file exists to catch: the rules engine is green, the "
                        + "settings screen saves happily, and every arriving message is treated "
                        + "as an interruption because no lane is ever asked for.")
                .isNotEmpty();

        // Named rather than merely counted, because "some class calls it" would still be
        // true if the only caller were an unreachable one.
        assertThat(callers).contains("com.jarurat.mailer.webmail.MailPollApi");
    }

    @Test
    @DisplayName("decideSendFailure() is called by production code, so a 3am failure can be quieted")
    void sendFailureHasAProductionCaller() throws Exception {
        Set<String> callers = callersOf("decideSendFailure");

        assertThat(callers)
                .as("PushService.sendFailed is building the failure notification without asking "
                        + "the rules for a lane, which is how a send that failed at three in the "
                        + "morning makes a sound at three in the morning.")
                .contains("com.jarurat.mailer.push.PushService");
    }

    // ==================================================================
    // The poll path, end to end through the controller
    // ==================================================================

    @Test
    @DisplayName("a Cc to fifty people is lane B, and the same message addressed to me is lane A")
    void theLaneFollowsTheRules() {
        Harness harness = new Harness();
        NotificationRules rules = new NotificationRules(USER);
        rules.setQuietEnabled(false);      // the clock is tested on its own, below
        harness.rules(rules);

        harness.message("""
                {"id":"e-1","receivedAt":"2026-08-29T09:14:02Z","subject":"Camp list",
                 "from":[{"email":"sunita@example.org"}],
                 "to":[{"email":"announce@example.org"}],"cc":[%s],"keywords":{}}"""
                .formatted(crowd(50)));
        Map<String, Object> bulk = harness.newest();
        assertThat(bulk.get("lane")).as("a Cc to a crowd must not buzz a phone").isEqualTo("B");
        assertThat(bulk.get("reason")).isEqualTo("not_direct");

        harness.message("""
                {"id":"e-2","receivedAt":"2026-08-29T09:15:02Z","subject":"Camp list",
                 "from":[{"email":"sunita@example.org"}],
                 "to":[{"email":"%s"},{"email":"anil@example.org"}],"cc":[],"keywords":{}}"""
                .formatted(USER));
        Map<String, Object> direct = harness.newest();
        assertThat(direct.get("lane")).isEqualTo("A");
        assertThat(direct.get("reason")).isEqualTo("direct_to_me");
    }

    @Test
    @DisplayName("inside quiet hours the same direct message is delivered silently, and says so")
    void quietHoursTakeTheSoundOff() {
        Harness harness = new Harness();
        NotificationRules rules = new NotificationRules(USER);
        // A window that contains this hour and the next one, in a fixed zone, so the
        // test cannot fail for two seconds a day as the clock rolls over.
        int hour = Instant.now().atZone(ZoneOffset.UTC).getHour();
        rules.setZoneId("UTC");
        rules.setQuietEnabled(true);
        rules.setQuietHours(hour, (hour + 2) % 24);
        harness.rules(rules);

        harness.message("""
                {"id":"e-3","receivedAt":"2026-08-29T02:40:00Z","subject":"Discharge summary",
                 "from":[{"email":"sunita@example.org"}],
                 "to":[{"email":"%s"}],"cc":[],"keywords":{}}""".formatted(USER));

        Map<String, Object> newest = harness.newest();
        // Not lane C. Quiet hours mute; they do not hide. The message is still shown in
        // full, which is the whole argument in NotificationRules.interrupt.
        assertThat(newest.get("lane")).isEqualTo("B");
        assertThat(newest.get("reason")).isEqualTo("direct_to_me");
        assertThat(newest.get("quiet")).isEqualTo(true);
    }

    @Test
    @DisplayName("a message another device has already read is lane C rather than a second alert")
    void aSeenMessageIsCountedAndNotAnnounced() {
        Harness harness = new Harness();
        NotificationRules rules = new NotificationRules(USER);
        rules.setQuietEnabled(false);
        harness.rules(rules);

        harness.message("""
                {"id":"e-4","receivedAt":"2026-08-29T09:14:02Z","subject":"Camp list",
                 "from":[{"email":"sunita@example.org"}],
                 "to":[{"email":"%s"}],"cc":[],"keywords":{"$seen":true}}""".formatted(USER));

        assertThat(harness.newest().get("lane")).isEqualTo("C");
        assertThat(harness.newest().get("reason")).isEqualTo("already_seen");
    }

    @Test
    @DisplayName("with no rules reachable the lane is null and never A")
    void anUnknownLaneSaysSo() {
        Harness harness = new Harness();      // no PushService wired in at all

        harness.message("""
                {"id":"e-5","receivedAt":"2026-08-29T09:14:02Z","subject":"Camp list",
                 "from":[{"email":"sunita@example.org"}],
                 "to":[{"email":"%s"}],"cc":[],"keywords":{}}""".formatted(USER));

        Map<String, Object> newest = harness.newest();
        // The poll still answers, because an unread count that stops arriving is worse
        // than a lane nobody can compute.
        assertThat(newest.get("id")).isEqualTo("e-5");
        assertThat(newest.get("lane"))
                .as("a lane that cannot be worked out must be reported as unknown. Defaulting "
                        + "it to A is how every message becomes an interruption again, and it "
                        + "is the reason people switch notifications off for good.")
                .isNull();
        assertThat(newest.get("reason")).isEqualTo("unknown");
    }

    // ==================================================================
    // Reading the call sites out of the compiled classes
    // ==================================================================

    /**
     * Every class under target/classes whose constant pool names NotificationRules and
     * this method together.
     *
     * NotificationRules itself is skipped, since its own class file names both by
     * definition, and so is anything else in that source file.
     */
    private static Set<String> callersOf(String method) throws Exception {
        Path root = classesRoot();
        Set<String> callers = new LinkedHashSet<>();
        try (Stream<Path> tree = Files.walk(root)) {
            List<Path> classFiles = tree.filter(p -> p.toString().endsWith(".class")).toList();
            for (Path file : classFiles) {
                String name = binaryName(root, file);
                if (name.equals("com.jarurat.mailer.push.NotificationRules")
                        || name.startsWith("com.jarurat.mailer.push.NotificationRules$")) {
                    continue;
                }
                if (methodRefs(file).contains(RULES + "#" + method)) callers.add(name);
            }
        }
        return callers;
    }

    /** target/classes, found through the compiled class rather than a hardcoded path. */
    private static Path classesRoot() throws Exception {
        return Path.of(NotificationRules.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
    }

    private static String binaryName(Path root, Path file) {
        String relative = root.relativize(file).toString();
        return relative.substring(0, relative.length() - ".class".length())
                .replace('\\', '.').replace('/', '.');
    }

    /**
     * Every method this class file refers to, as owner#name.
     *
     * A hand-rolled constant pool walk rather than a bytecode library, because the only
     * question being asked is which methods are named, the format has not changed since
     * Java 1.0, and the alternative is a new dependency or the repackaged copy of ASM
     * inside spring-core, which is internal and could be gone in any release. The
     * structure is JVMS 4.4: a tag, then a fixed shape per tag, with longs and doubles
     * taking two slots because of a decision made in 1995.
     */
    private static Set<String> methodRefs(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != 0xCAFEBABE) return Set.of();
            in.readUnsignedShort();     // minor version
            in.readUnsignedShort();     // major version

            int count = in.readUnsignedShort();
            int[] tags = new int[count];
            String[] utf8 = new String[count];
            int[][] indexes = new int[count][];

            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                tags[i] = tag;
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 3, 4 -> in.readInt();
                    case 5, 6 -> {
                        in.readLong();
                        i++;            // eight byte constants occupy two entries
                    }
                    case 7, 8, 16, 19, 20 -> indexes[i] = new int[]{in.readUnsignedShort()};
                    case 9, 10, 11, 12, 17, 18 -> indexes[i] =
                            new int[]{in.readUnsignedShort(), in.readUnsignedShort()};
                    case 15 -> {
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                    }
                    default -> throw new IOException(
                            "Unknown constant pool tag " + tag + " in " + file);
                }
            }

            Set<String> refs = new LinkedHashSet<>();
            for (int i = 1; i < count; i++) {
                // 10 is a method on a class, 11 the same on an interface.
                if (tags[i] != 10 && tags[i] != 11) continue;
                String owner = utf8[indexes[indexes[i][0]][0]];
                String name = utf8[indexes[indexes[i][1]][0]];
                refs.add(owner + "#" + name);
            }
            return refs;
        }
    }

    // ==================================================================
    // One controller, one canned mailbox
    // ==================================================================

    /**
     * The poll controller wired to a JMAP spy, the same arrangement MailPollApiTest
     * uses: the request builders and the reply reader are real, and only the socket is
     * stubbed, so what these tests read is the answer the controller genuinely produces.
     *
     * PushService is a mock rather than the real service because nothing here is about
     * push delivery: the only thing the poll path asks it for is a rules row, and a mock
     * is how a test says which row without standing up a database.
     */
    private static final class Harness {

        private final ObjectMapper json = new ObjectMapper();
        private final MailboxAccess mailbox = mock(MailboxAccess.class);
        private final MailService mail = mock(MailService.class);
        private final JmapClient jmap = spy(new JmapClient(
                new InMemoryMailCredentialStore(), "https://127.0.0.1/jmap/", 1, 1));
        private final AtomicReference<String> reply = new AtomicReference<>("");
        private final HttpSession session = new MockHttpSession();
        private final MailPollApi controller;

        Harness() {
            doReturn(new MailSession(USER, "g", URI.create("https://localhost/jmap/"), "", "", "s1"))
                    .when(jmap).session(anyString());
            doAnswer(call -> json.readTree(reply.get()).path("methodResponses"))
                    .when(jmap).call(anyString(), anyList(), any(ArrayNode.class));
            when(mailbox.require(any(), any())).thenReturn(USER);
            when(mail.folderByRole(USER, "inbox"))
                    .thenReturn(new MailFolder(INBOX, "Inbox", "inbox", null, 0, 812, 3));
            controller = new MailPollApi(mailbox, mail, jmap);
        }

        /**
         * The rules this poll will find. Set on the field rather than through the
         * constructor because the constructor is a contract MailPollApiTest already
         * holds, and widening it to carry a notification dependency would break a file
         * this change does not own.
         */
        void rules(NotificationRules rules) {
            PushService push = mock(PushService.class);
            when(push.rulesFor(anyString())).thenReturn(rules);
            ReflectionTestUtils.setField(controller, "push", push);
        }

        void message(String emailJson) {
            reply.set("""
                    {"methodResponses":[
                      ["Mailbox/get",{"list":[
                         {"id":"%s","role":"inbox","totalEmails":812,"unreadEmails":3}]},"p0"],
                      ["Email/query",{"ids":["e-1"]},"q0"],
                      ["Email/get",{"list":[%s]},"g0"]]}""".formatted(INBOX, emailJson));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> newest() {
            return (Map<String, Object>) controller.poll(null, session).get("newest");
        }
    }

    /** A Cc line long enough that being on it says nothing about you. */
    private static String crowd(int howMany) {
        List<String> entries = new ArrayList<>(howMany);
        for (int i = 0; i < howMany; i++) entries.add("{\"email\":\"person" + i + "@example.org\"}");
        return String.join(",", entries);
    }
}
