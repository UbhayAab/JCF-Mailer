package com.jarurat.mailer.webmail;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recipient autocomplete for the compose box.
 *
 * ==================================================================
 * THE CONTRACT. The client half of this is written against the text
 * below and nothing else, so it is normative rather than descriptive.
 * ==================================================================
 *
 * GET /api/mail/contacts?q=&lt;prefix&gt;&amp;limit=&lt;n&gt;
 *
 * REQUEST
 *   q      Optional, defaults to "". The prefix the person has typed into the To,
 *          Cc or Bcc field. Sent raw; no need to lowercase, trim or strip accents,
 *          the server does all three.
 *   limit  Optional, defaults to 8. Clamped server side to 1..25. Anything that is
 *          not a number is ignored and the default is used, so a bad value can
 *          never turn into an error status.
 *   There is no mailbox parameter and there will never be one. The mailbox is the
 *   one pinned in the browser session, for the reason MailboxAccess documents at
 *   length: a mailbox id on the query string would be a way for a signed-in user
 *   to read a mailbox whose password they never produced.
 *   No CSRF token is needed. It is a GET and the chain exempts those.
 *
 * RESPONSE
 *   Always HTTP 200, always application/json, always this shape:
 *
 *   {
 *     "q": "pri",
 *     "locked": false,
 *     "contacts": [
 *       { "email": "priya@jarurat.care",   "name": "Priya Sharma", "lastSeen": "2026-08-27T09:14:02Z" },
 *       { "email": "support@jarurat.care", "name": "",             "lastSeen": "" }
 *     ]
 *   }
 *
 *   q         The query as the server understood it, trimmed. Echoed so a client
 *             that fires on every keystroke can throw away an answer that has been
 *             overtaken by later typing, rather than letting a slow early response
 *             land on top of a fast later one.
 *   locked    true when this session has no mailbox open. contacts is then always
 *             empty. It is a hint for hiding the dropdown rather than showing an
 *             empty one, not an error: this endpoint does not 409 the way the rest
 *             of /api/mail does, because an autocomplete must never be the thing
 *             that throws an unlock sheet over somebody mid-sentence.
 *   contacts  Ranked, best first. Never null. May be empty. At most limit entries.
 *   email     Lowercase, always present, never empty, unique within the list. This
 *             is the value to insert into the field.
 *   name      Display name, or "" when none has ever been seen for that address.
 *             Never null. Not HTML escaped, so escape it before inserting it into
 *             the DOM; use textContent rather than innerHTML.
 *   lastSeen  ISO-8601 instant in UTC, e.g. "2026-08-27T09:14:02Z", or "" when the
 *             address came from the directory and has no correspondence behind it.
 *             Never null. Suitable for a muted "last wrote 3 days ago" line.
 *
 * STATUSES THE CLIENT MUST HANDLE
 *   200  Always, including no mailbox open, a mail server that is down, a cold
 *        cache and a query that matches nothing. All four answer with an empty
 *        contacts array. There is no 400, no 409, no 500 and no 502 from here.
 *   401  Console session has expired. The browser should go to /login. Produced by
 *        the security filter chain, not by this class.
 *   403  Session lacks MAIL_READ. Same, and not something a mail session ever sees.
 *
 * BEHAVIOUR WORTH KNOWING WHEN WIRING THE BOX
 *   - The first call after a mailbox is opened is likely to return an empty list.
 *     Building the address book means reading Sent and Inbox, and this endpoint
 *     will not wait more than a few hundred milliseconds for that, so it answers
 *     empty and lets the harvest finish behind it. Do NOT treat an empty first
 *     answer as "this mailbox has no contacts" and stop asking: query again on the
 *     next keystroke and it will be populated. Firing one throwaway request when
 *     the composer opens is a cheap way to have the list ready by the first letter.
 *   - Once warm, a call costs no round trip to the mail server at all, so a short
 *     debounce is about your own render cost rather than about protecting anything
 *     here. 120ms or so is plenty; the endpoint is not rate limited.
 *   - q="" returns the top contacts by rank, so the box can show recent recipients
 *     on focus before anything has been typed.
 *   - Matching is prefix only, never substring, and it matches against the whole
 *     address, the part before the @, the whole display name and each word of the
 *     display name. It is case and accent insensitive, so "jose" finds a Jose
 *     spelled with an accent. A prefix beginning with "@" matches the domain, so
 *     "@jar" is how a person asks for anyone internal.
 *   - The open mailbox's own address is never in the list.
 *   - Ranking is recency and frequency together, weighted towards people this
 *     mailbox has written to rather than merely heard from. The order is stable
 *     between calls, so the dropdown does not reshuffle under the cursor.
 *
 * ==================================================================
 *
 * Separate from MailApiController on purpose. That class turns a MailException
 * into a status the screen acts on, which is right for every route it owns and
 * wrong for this one: this route's whole contract is that it never reports a
 * failure at all, and sharing a class would mean sharing the handlers that do.
 */
@RestController
@RequestMapping("/api/mail")
public class ContactSuggestApi {

    private static final int DEFAULT_LIMIT = 8;

    /**
     * Enough to fill a dropdown twice over. The cap is here because the response is
     * built in memory and because a list longer than a screen is not an autocomplete
     * any more, not because anything downstream would struggle.
     */
    private static final int MAX_LIMIT = 25;

    private final ContactSuggestService contacts;
    private final MailboxAccess mailbox;

    public ContactSuggestApi(ContactSuggestService contacts, MailboxAccess mailbox) {
        this.contacts = contacts;
        this.mailbox = mailbox;
    }

    /**
     * The endpoint. MAIL_READ and nothing stronger, because it reads no message and
     * changes nothing: it reports addresses out of mail this session can already open
     * and read in full.
     *
     * mailbox.current rather than mailbox.require, and that is the difference between
     * this route and every other one. require throws MailboxLockedException so the
     * screen can put up the unlock sheet, which is exactly right when somebody has
     * clicked a folder and exactly wrong when they are halfway through typing a
     * recipient. A locked mailbox here is a quiet empty list with a flag on it.
     *
     * Nothing is audited. It fires once per keystroke, so a row per call would bury
     * the audit log in noise, and it reveals nothing that reading the folder it came
     * from would not. MAIL_ATTACHMENT_DOWNLOADED is the audited one because that
     * moves bytes out of the mailbox; this moves addresses the session already has.
     */
    @GetMapping(value = "/contacts", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('MAIL_READ')")
    public Map<String, Object> contacts(Authentication auth,
                                        HttpSession session,
                                        @RequestParam(defaultValue = "") String q,
                                        @RequestParam(defaultValue = "") String limit) {
        String query = q == null ? "" : q.trim();
        String open = mailbox.current(auth, session);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("q", query);
        out.put("locked", open == null);
        out.put("contacts", open == null
                ? List.of()
                : rows(contacts.suggest(open, query, clampLimit(limit))));
        return out;
    }

    /**
     * limit arrives as text and is parsed here rather than bound as an int by Spring.
     *
     * Binding it would mean limit=abc failing inside the framework, before this method
     * is entered, and coming back as the 400 MailApiController.onBadParam produces.
     * That would make the promise at the top of this file, that the only status this
     * route ever produces is 200, depend on an exception handler firing correctly. It
     * is a better contract if there is simply no way to reach the failure.
     */
    private static int clampLimit(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_LIMIT;
        try {
            return Math.min(MAX_LIMIT, Math.max(1, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return DEFAULT_LIMIT;
        }
    }

    private static List<Map<String, Object>> rows(List<ContactSuggestService.Contact> found) {
        List<Map<String, Object>> rows = new ArrayList<>(found.size());
        for (ContactSuggestService.Contact c : found) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("email", nz(c.email()));
            row.put("name", nz(c.name()));
            row.put("lastSeen", iso(c.lastSeen()));
            rows.add(row);
        }
        return rows;
    }

    /**
     * The last line of the promise above.
     *
     * ContactSuggestService already swallows everything it can hit, so in practice
     * this handler should never run. It is here because "never 500 into the UI" is a
     * contract another agent is coding against and a contract that holds only as long
     * as nobody adds a throwing line to the method above is not much of one. An empty
     * list is the correct failure, so an empty list is what a fault produces.
     *
     * AccessDeniedException is rethrown for the same reason MailApiController.onFailure
     * rethrows it: a @PreAuthorize denial arrives here as a RuntimeException, and
     * swallowing it would turn every 403 into a cheerful 200 with an empty list, which
     * is a permission check that has stopped checking anything.
     */
    @ExceptionHandler(RuntimeException.class)
    public Map<String, Object> onFailure(RuntimeException e) {
        if (e instanceof AccessDeniedException) throw e;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("q", "");
        out.put("locked", false);
        out.put("contacts", List.of());
        return out;
    }

    private static String iso(Instant when) {
        return when == null ? "" : when.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
