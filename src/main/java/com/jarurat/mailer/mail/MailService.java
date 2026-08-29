package com.jarurat.mailer.mail;

import com.jarurat.mailer.services.SesSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The mailbox operations the webmail needs, expressed in our own types.
 *
 * Almost nothing here fans out onto threads, and that is deliberate rather than
 * lazy: JMAP lets one request carry several method calls and lets a later call
 * feed off an earlier one's result, so listing a folder is Email/query plus
 * Email/get in a single round trip. Batching beats parallelism when the cost is
 * the round trip. Virtual threads are used where there genuinely are independent
 * round trips, which is only the cross-account fan-out at the bottom of this file.
 */
@Service
public class MailService {

    private static final List<String> MAIL_CAPS = List.of(JmapClient.CORE, JmapClient.MAIL);
    private static final List<String> SEND_CAPS = List.of(JmapClient.CORE, JmapClient.MAIL, JmapClient.SUBMISSION);
    private static final List<String> IDENTITY_CAPS = List.of(JmapClient.CORE, JmapClient.SUBMISSION);

    private static final List<String> SUMMARY_PROPS = List.of(
            "id", "threadId", "mailboxIds", "keywords", "size", "receivedAt",
            "subject", "from", "to", "cc", "preview", "hasAttachment");

    private static final List<String> BODY_PROPS = List.of(
            "id", "threadId", "mailboxIds", "keywords", "subject", "from", "to", "cc", "bcc", "replyTo",
            "sentAt", "receivedAt", "textBody", "htmlBody", "bodyValues", "attachments",
            "messageId", "inReplyTo", "references");

    /** Keywords the UI refers to by bare name. Anything else is a user label and is left alone. */
    private static final Set<String> SYSTEM_KEYWORDS =
            Set.of("seen", "flagged", "answered", "draft", "forwarded");

    /**
     * Every folder on this server reports sortOrder 0 (measured), so ordering by
     * it gives whatever RocksDB felt like. Order by role instead, the way every
     * mail client does, and fall back to name for user-created folders.
     */
    private static final List<String> ROLE_ORDER = List.of("inbox", "drafts", "sent", "archive", "junk", "trash");

    private static final Comparator<MailFolder> FOLDER_ORDER = Comparator
            .comparingInt((MailFolder f) -> {
                int i = f.role() == null ? -1 : ROLE_ORDER.indexOf(f.role().toLowerCase(Locale.ROOT));
                return i < 0 ? ROLE_ORDER.size() : i;
            })
            .thenComparing((MailFolder f) -> f.name() == null ? "" : f.name(), String.CASE_INSENSITIVE_ORDER);

    /** How long a mailbox id or a send identity is trusted without asking again. */
    private static final long CACHE_SECONDS = 300;

    private record Cached<T>(T value, Instant expiresAt) {
        boolean isStale() { return Instant.now().isAfter(expiresAt); }
    }

    private final Map<String, Cached<Map<String, String>>> roleCache = new ConcurrentHashMap<>();
    private final Map<String, Identity> identityCache = new ConcurrentHashMap<>();

    private final JmapClient client;
    private final SesSender ses;
    private final int maxPageSize;
    private final int maxBodyBytes;
    private final long maxAttachmentBytes;

    public MailService(JmapClient client,
                       SesSender ses,
                       @Value("${jarurat.mail.max-page-size:100}") int maxPageSize,
                       @Value("${jarurat.mail.max-body-bytes:1000000}") int maxBodyBytes,
                       @Value("${jarurat.mail.max-attachment-bytes:17825792}") long maxAttachmentBytes) {
        this.client = client;
        this.ses = ses;
        this.maxPageSize = Math.max(1, maxPageSize);
        this.maxBodyBytes = Math.max(4096, maxBodyBytes);
        this.maxAttachmentBytes = maxAttachmentBytes > 0 ? maxAttachmentBytes : 17_825_792L;
    }

    /**
     * How many raw bytes of attachment one message may carry, added up across every
     * file on it. Deliberately smaller than the upload limit, and the arithmetic is
     * the reason rather than caution.
     *
     * A MIME attachment travels base64 encoded, which is four bytes out for every
     * three in plus a CRLF every 76 characters: about 1.37 times its size on disk.
     * The limit almost every receiving server enforces is 25MB and it is enforced on
     * the encoded message, so the default here of 17MiB leaves at 24.4MB and just
     * fits, with the rest of that 25MB left for the body and the headers. The 25MB
     * the upload path allows would leave as 34MB and be refused by the far end after
     * we had already accepted it, spent the sender's minutes on it and filed a copy
     * in Sent. Refusing before anything is uploaded is the same answer, earlier.
     */
    public long maxAttachmentBytes() { return maxAttachmentBytes; }

    /** The size the same bytes will have once base64 encoded into a MIME message. */
    public static long encodedSize(long rawBytes) {
        return Math.round(rawBytes * 1.37d);
    }

    // ------------------------------------------------------------------
    // Folders
    // ------------------------------------------------------------------

    public List<MailFolder> listFolders(String user) {
        ObjectNode args = client.accountArgs(user);
        args.putNull("ids");
        ArrayNode props = args.putArray("properties");
        for (String p : List.of("id", "name", "role", "parentId", "sortOrder", "totalEmails", "unreadEmails")) {
            props.add(p);
        }

        JsonNode result = client.response(
                client.call(user, MAIL_CAPS, client.newArray().add(client.invocation("Mailbox/get", args, "f0"))),
                "Mailbox/get", "f0");

        List<MailFolder> folders = new ArrayList<>();
        for (JsonNode m : result.path("list")) {
            folders.add(new MailFolder(
                    JmapClient.text(m, "id"),
                    JmapClient.text(m, "name"),
                    JmapClient.text(m, "role"),
                    JmapClient.text(m, "parentId"),
                    m.path("sortOrder").asInt(0),
                    m.path("totalEmails").asInt(0),
                    m.path("unreadEmails").asInt(0)));
        }
        folders.sort(FOLDER_ORDER);
        return List.copyOf(folders);
    }

    /** Null when the account has no folder with that role, which should never happen but does not deserve a crash. */
    public MailFolder folderByRole(String user, String role) {
        for (MailFolder f : listFolders(user)) {
            if (f.isRole(role)) return f;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    public MessagePage listMessages(String user, String folderId, int offset, int limit) {
        if (folderId == null || folderId.isBlank()) {
            throw new MailException(MailException.Kind.PROTOCOL, "No folder given");
        }
        ObjectNode filter = client.newObject();
        filter.put("inMailbox", folderId);
        return page(user, filter, offset, limit);
    }

    /**
     * Stalwart's "text" filter covers subject, addresses and body at once, which
     * is what a single search box means to a user. A blank query deliberately
     * matches everything rather than erroring, so clearing the box returns to the
     * full mailbox.
     */
    public MessagePage search(String user, String query, int offset, int limit) {
        ObjectNode filter = client.newObject();
        if (query != null && !query.isBlank()) filter.put("text", query.trim());
        return page(user, filter, offset, limit);
    }

    private MessagePage page(String user, ObjectNode filter, int offset, int limit) {
        int from = Math.max(0, offset);
        int size = Math.min(Math.max(1, limit), maxPageSize);

        ObjectNode query = client.accountArgs(user);
        query.set("filter", filter);
        query.putArray("sort").addObject().put("property", "receivedAt").put("isAscending", false);
        query.put("position", from);
        query.put("limit", size);
        query.put("calculateTotal", true);

        // Back reference: Email/get reads the ids Email/query just produced, so the
        // whole page costs one round trip instead of two.
        ObjectNode get = client.accountArgs(user);
        ObjectNode ref = get.putObject("#ids");
        ref.put("resultOf", "q0");
        ref.put("name", "Email/query");
        ref.put("path", "/ids");
        ArrayNode props = get.putArray("properties");
        for (String p : SUMMARY_PROPS) props.add(p);

        JsonNode responses = client.call(user, MAIL_CAPS, client.newArray()
                .add(client.invocation("Email/query", query, "q0"))
                .add(client.invocation("Email/get", get, "g0")));

        JsonNode queried = client.response(responses, "Email/query", "q0");
        JsonNode fetched = client.response(responses, "Email/get", "g0");

        // Email/get makes no promise about ordering, so re-impose the query's order.
        Map<String, JsonNode> byId = new HashMap<>();
        for (JsonNode e : fetched.path("list")) byId.put(JmapClient.text(e, "id"), e);

        List<MessageSummary> rows = new ArrayList<>();
        for (JsonNode id : queried.path("ids")) {
            JsonNode e = byId.get(JmapClient.string(id));
            if (e != null) rows.add(toSummary(e));
        }
        return new MessagePage(List.copyOf(rows), from, size, queried.path("total").asInt(rows.size()));
    }

    public MessageBody getMessage(String user, String emailId) {
        ObjectNode args = client.accountArgs(user);
        args.putArray("ids").add(emailId);
        ArrayNode props = args.putArray("properties");
        for (String p : BODY_PROPS) props.add(p);
        args.put("fetchTextBodyValues", true);
        args.put("fetchHTMLBodyValues", true);
        // A runaway body must not be able to exhaust heap on a t4g.small.
        args.put("maxBodyValueBytes", maxBodyBytes);

        JsonNode result = client.response(
                client.call(user, MAIL_CAPS, client.newArray().add(client.invocation("Email/get", args, "b0"))),
                "Email/get", "b0");

        JsonNode e = result.path("list").path(0);
        if (e.isMissingNode() || e.isNull()) {
            throw new MailException(MailException.Kind.NOT_FOUND,
                    "No message " + emailId + " in " + user + "'s mailbox");
        }

        JsonNode values = e.path("bodyValues");
        JsonNode keywords = e.path("keywords");

        return new MessageBody(
                JmapClient.text(e, "id"),
                JmapClient.text(e, "threadId"),
                JmapClient.text(e, "subject"),
                addresses(e.path("from")), addresses(e.path("to")),
                addresses(e.path("cc")), addresses(e.path("bcc")), addresses(e.path("replyTo")),
                instant(e, "sentAt"), instant(e, "receivedAt"),
                bodyOfType(e.path("htmlBody"), values, "text/html"),
                bodyOfType(e.path("textBody"), values, "text/plain"),
                attachments(e.path("attachments")),
                mailboxIds(e.path("mailboxIds")),
                keywords.path("$seen").asBoolean(false),
                keywords.path("$flagged").asBoolean(false),
                keywords.path("$draft").asBoolean(false),
                firstString(e.path("messageId")),
                strings(e.path("inReplyTo")),
                strings(e.path("references")));
    }

    // ------------------------------------------------------------------
    // Changing state
    // ------------------------------------------------------------------

    public void setKeyword(String user, String emailId, String keyword, boolean value) {
        String flag = normaliseKeyword(keyword);
        ObjectNode args = client.accountArgs(user);
        ObjectNode patch = args.putObject("update").putObject(emailId);
        // JMAP clears a keyword by patching it to null. Patching it to false sets a
        // keyword whose value is false, which is not the same thing.
        if (value) patch.put("keywords/" + flag, true); else patch.putNull("keywords/" + flag);
        applySet(user, args, "k0", emailId);
    }

    public void move(String user, String emailId, String targetFolderId) {
        if (targetFolderId == null || targetFolderId.isBlank()) {
            throw new MailException(MailException.Kind.PROTOCOL, "No target folder given");
        }
        ObjectNode args = client.accountArgs(user);
        // Replace mailboxIds wholesale rather than patching, so a message that was
        // filed in two folders ends up in exactly the one the user picked.
        args.putObject("update").putObject(emailId).putObject("mailboxIds").put(targetFolderId, true);
        applySet(user, args, "m0", emailId);
    }

    /**
     * Delete means "move to Trash" the first time and "really destroy" once the
     * message is already in Trash. That is what every mail client does, and it is
     * the difference between a recoverable mistake and a permanent one.
     */
    public void delete(String user, String emailId) {
        MailFolder trash = folderByRole(user, "trash");
        if (trash == null) {
            purge(user, emailId);
            return;
        }
        if (folderIdsOf(user, emailId).contains(trash.id())) {
            purge(user, emailId);
        } else {
            move(user, emailId, trash.id());
        }
    }

    /** Unrecoverable. Only for an explicit "delete forever". */
    public void purge(String user, String emailId) {
        ObjectNode args = client.accountArgs(user);
        args.putArray("destroy").add(emailId);
        applySet(user, args, "d0", emailId);
    }

    private void applySet(String user, ObjectNode args, String callId, String id) {
        JsonNode result = client.response(
                client.call(user, MAIL_CAPS, client.newArray().add(client.invocation("Email/set", args, callId))),
                "Email/set", callId);

        // A JMAP set answers HTTP 200 and can still have refused the one object we
        // asked about, so the per-object rejection maps have to be checked.
        JsonNode refused = result.path("notUpdated").path(id);
        if (refused.isMissingNode() || refused.isNull()) refused = result.path("notDestroyed").path(id);
        if (refused.isMissingNode() || refused.isNull()) return;

        String type = JmapClient.text(refused, "type");
        throw new MailException(
                "notFound".equals(type) ? MailException.Kind.NOT_FOUND : MailException.Kind.METHOD,
                type, "Email/set refused " + id + ": " + (type == null ? "unknown reason" : type), null);
    }

    private List<String> folderIdsOf(String user, String emailId) {
        ObjectNode args = client.accountArgs(user);
        args.putArray("ids").add(emailId);
        args.putArray("properties").add("id").add("mailboxIds");

        JsonNode result = client.response(
                client.call(user, MAIL_CAPS, client.newArray().add(client.invocation("Email/get", args, "w0"))),
                "Email/get", "w0");

        JsonNode e = result.path("list").path(0);
        if (e.isMissingNode() || e.isNull()) {
            throw new MailException(MailException.Kind.NOT_FOUND,
                    "No message " + emailId + " in " + user + "'s mailbox");
        }
        return mailboxIds(e.path("mailboxIds"));
    }

    // ------------------------------------------------------------------
    // Identities and sending
    // ------------------------------------------------------------------

    /** What the compose screen puts in its From dropdown. Stalwart decides this, not our user table. */
    public List<Identity> listIdentities(String user) {
        ObjectNode args = client.accountArgs(user);
        args.putNull("ids");

        JsonNode result = client.response(
                client.call(user, IDENTITY_CAPS, client.newArray().add(client.invocation("Identity/get", args, "i0"))),
                "Identity/get", "i0");

        List<Identity> out = new ArrayList<>();
        for (JsonNode i : result.path("list")) {
            out.add(new Identity(
                    JmapClient.text(i, "id"), JmapClient.text(i, "name"), JmapClient.text(i, "email"),
                    JmapClient.text(i, "replyTo"),
                    JmapClient.text(i, "textSignature"), JmapClient.text(i, "htmlSignature")));
        }
        return List.copyOf(out);
    }

    /**
     * Sends and files the result in Sent, in one request.
     *
     * The message is created in Drafts and submitted in the same call, with
     * onSuccessUpdateEmail moving it to Sent and clearing $draft only if the
     * submission was actually accepted. Doing it in two requests would leave a
     * ghost draft behind every time submission failed.
     *
     * Returns the JMAP id of the sent message.
     */
    public String send(String user, List<String> to, List<String> cc, String subject,
                       String htmlBody, String textBody) {
        return send(user, Outgoing.message(to, cc, subject, htmlBody, textBody));
    }

    /**
     * The same send, carrying files.
     *
     * Each Attachment must already name a blob this account owns, which is what
     * JmapClient.upload returns. Nothing here reads bytes: the message references
     * blobs the mail server is already holding, so a 20MB attachment crosses this
     * process once, on its way to the blob store, and never again.
     *
     * The five argument overload above is the whole of the previous contract and
     * still exists for every caller that has nothing to attach.
     */
    public String send(String user, List<String> to, List<String> cc, String subject,
                       String htmlBody, String textBody, List<Attachment> attachments) {
        return send(user, Outgoing.message(to, cc, subject, htmlBody, textBody)
                .withAttachments(attachments));
    }

    /**
     * The whole of the send contract, blind copies and threading headers included.
     *
     * The blind copy guarantee lives in this method and is structural rather than
     * procedural, which is the only kind worth making. The Email object built below
     * has a to and it has a cc and there is no line anywhere in this class that puts
     * a bcc property on it, so there is no Bcc header for Stalwart to render into the
     * MIME and therefore nothing for any recipient's client to display. The blind
     * addresses appear once, in envelope.rcptTo on the EmailSubmission, which is the
     * SMTP conversation and is never part of the message any recipient receives. That
     * also means the copy filed in Sent carries no record of who was blind copied,
     * which is a real cost and is why every blind address is written to the audit log
     * and the message log instead, where it belongs and where nobody outside the
     * organisation can read it.
     */
    public String send(String user, Outgoing message) {
        List<String> envelope = cleanAddresses(message.everyRecipient());
        if (envelope.isEmpty()) {
            throw new MailException(MailException.Kind.PROTOCOL, "A message needs at least one recipient");
        }
        if (envelope.size() > Outgoing.MAX_RECIPIENTS) {
            throw new MailException(MailException.Kind.PROTOCOL,
                    "That message names " + envelope.size() + " recipients and one message may carry "
                            + Outgoing.MAX_RECIPIENTS + ". Send a list this size from Campaign Studio, "
                            + "which throttles it and records what happened to each address.");
        }

        Identity identity = defaultIdentity(user);
        Map<String, String> roles = roleFolderIds(user);
        String drafts = roles.get("drafts");
        String sent = roles.get("sent");
        if (drafts == null) {
            throw new MailException(MailException.Kind.NOT_FOUND, "No Drafts folder in " + user + "'s mailbox");
        }

        ObjectNode draft = buildEmail(identity, drafts, message, false);

        ObjectNode create = client.accountArgs(user);
        create.putObject("create").set("draft", draft);

        ObjectNode submit = client.accountArgs(user);
        ObjectNode submission = submit.putObject("create").putObject("sub");
        submission.put("emailId", "#draft");
        submission.put("identityId", identity.id());
        ObjectNode envelopeNode = submission.putObject("envelope");
        envelopeNode.putObject("mailFrom").put("email", identity.email());
        ArrayNode rcpt = envelopeNode.putArray("rcptTo");
        // The one place a blind address is named, and it is the SMTP envelope rather
        // than the message. RFC 8621 lets the envelope be omitted and derived from the
        // header addresses, and this is exactly why it must not be: a derived envelope
        // can only reach the people the headers name, so a blind copy would silently
        // never be delivered at all.
        for (String address : envelope) rcpt.addObject().put("email", address);

        if (sent != null) {
            ObjectNode onSuccess = submit.putObject("onSuccessUpdateEmail").putObject("#sub");
            onSuccess.putNull("mailboxIds/" + drafts);
            onSuccess.put("mailboxIds/" + sent, true);
            onSuccess.putNull("keywords/$draft");
        }

        JsonNode responses = client.call(user, SEND_CAPS, client.newArray()
                .add(client.invocation("Email/set", create, "c0"))
                .add(client.invocation("EmailSubmission/set", submit, "s0")));

        JsonNode created = client.response(responses, "Email/set", "c0");
        JsonNode refusedDraft = created.path("notCreated").path("draft");
        if (!refusedDraft.isMissingNode() && !refusedDraft.isNull()) {
            throw new MailException(MailException.Kind.METHOD, JmapClient.text(refusedDraft, "type"),
                    "Mail server refused the message: " + JmapClient.text(refusedDraft, "type"), null);
        }

        JsonNode submitted = client.response(responses, "EmailSubmission/set", "s0");
        JsonNode refusedSend = submitted.path("notCreated").path("sub");
        if (!refusedSend.isMissingNode() && !refusedSend.isNull()) {
            throw new MailException(MailException.Kind.METHOD, JmapClient.text(refusedSend, "type"),
                    "Mail server accepted the message but refused to send it: "
                            + JmapClient.text(refusedSend, "type"), null);
        }

        return JmapClient.text(created.path("created").path("draft"), "id");
    }

    // ------------------------------------------------------------------
    // Drafts
    // ------------------------------------------------------------------

    /**
     * Writes a draft into the Drafts mailbox and returns the id it now has.
     *
     * There is no Postgres table behind this and there must never be one. A draft is
     * mailbox state: the whole reason to save one is that it is on the laptop when it
     * was written on a phone, and the moment a second copy lives in our database the
     * two disagree the first time somebody opens the same account in Thunderbird.
     *
     * Passing an existing draftId replaces that draft, and the replacement is a create
     * followed by a destroy rather than an update because RFC 8621 makes an Email
     * immutable apart from mailboxIds and keywords. There is no way to change the body
     * of a message that exists, so the JMAP shape of "edit a draft" is genuinely a new
     * message and the deletion of the old one. Both go in one Email/set, where the
     * specified processing order is create before destroy, so the new draft is safely
     * on the server before the old one stops existing and a failure halfway leaves the
     * sender with a duplicate rather than with nothing.
     */
    public String saveDraft(String user, String draftId, Outgoing message) {
        Identity identity = defaultIdentity(user);
        String drafts = roleFolderIds(user).get("drafts");
        if (drafts == null) {
            throw new MailException(MailException.Kind.NOT_FOUND, "No Drafts folder in " + user + "'s mailbox");
        }

        ObjectNode args = client.accountArgs(user);
        args.putObject("create").set("draft", buildEmail(identity, drafts, message, true));
        if (draftId != null && !draftId.isBlank()) args.putArray("destroy").add(draftId);

        JsonNode result = client.response(
                client.call(user, MAIL_CAPS, client.newArray().add(client.invocation("Email/set", args, "v0"))),
                "Email/set", "v0");

        JsonNode refused = result.path("notCreated").path("draft");
        if (!refused.isMissingNode() && !refused.isNull()) {
            throw new MailException(MailException.Kind.METHOD, JmapClient.text(refused, "type"),
                    "Mail server refused the draft: " + JmapClient.text(refused, "type"), null);
        }
        // A refusal to destroy the previous version is deliberately not fatal. The only
        // way it happens is that the id is already gone, which is the state we wanted,
        // and failing the save would lose the text the sender has just typed over a
        // message that says the old copy could not be deleted.
        return JmapClient.text(result.path("created").path("draft"), "id");
    }

    /** Throws the draft away. Used when a draft is sent, and when the sender discards one. */
    public void deleteDraft(String user, String draftId) {
        if (draftId == null || draftId.isBlank()) return;
        purge(user, draftId);
    }

    // ------------------------------------------------------------------
    // Building the Email object
    // ------------------------------------------------------------------

    /**
     * The Email object a send and a draft save both produce, so the two cannot drift.
     *
     * asDraft is the only thing that decides whether a Bcc header is written, and the
     * distinction is the whole of the blind copy guarantee. A draft is a file in the
     * sender's own mailbox that is never transmitted to anybody, so a Bcc header on one
     * is private notepaper and is the only way a draft written on a phone still knows
     * who it was going to blind copy when it is opened on a laptop. A send is a
     * transmission, so the header is not written at all: the blind addresses reach the
     * SMTP envelope in send() and nowhere else, which means there is no Bcc header for
     * the mail server to render into the MIME and therefore nothing any recipient's
     * client could display even if it wanted to. The send path is asserted by a test
     * that walks this JSON looking for the string bcc and fails if it finds it.
     */
    private ObjectNode buildEmail(Identity identity, String mailboxId, Outgoing message, boolean asDraft) {
        List<String> recipients = cleanAddresses(message.to());
        List<String> copies = cleanAddresses(message.cc());

        ObjectNode draft = client.newObject();
        draft.putObject("mailboxIds").put(mailboxId, true);
        ObjectNode keywords = draft.putObject("keywords");
        keywords.put("$draft", true);
        keywords.put("$seen", true);
        draft.putArray("from").addObject()
                .put("name", identity.name() == null ? "" : identity.name())
                .put("email", identity.email());
        // An empty array is not the same as an absent header, and a To with no
        // addresses in it is what a blind copy only message would otherwise carry.
        if (!recipients.isEmpty()) addressArray(draft.putArray("to"), recipients);
        if (!copies.isEmpty()) addressArray(draft.putArray("cc"), copies);
        if (asDraft) {
            List<String> blind = cleanAddresses(message.bcc());
            if (!blind.isEmpty()) addressArray(draft.putArray("bcc"), blind);
        }
        if (identity.replyTo() != null && !identity.replyTo().isBlank()) {
            addressArray(draft.putArray("replyTo"), List.of(identity.replyTo()));
        }
        draft.put("subject", message.subject());

        // In-Reply-To and References are what actually make a conversation, and the
        // subject line is not: a recipient's client groups on a message id shared
        // between these two headers, so a reply that omits them opens a new thread on
        // the recipient's screen no matter how many times "Re:" is prefixed. References
        // carries the parent's own chain plus the parent, which is what lets a client
        // rebuild the middle of a conversation it was only copied into halfway.
        if (message.isThreaded()) {
            draft.putArray("inReplyTo").add(message.inReplyTo());
            ArrayNode refs = draft.putArray("references");
            for (String id : threadReferences(message)) refs.add(id);
        }

        ObjectNode values = client.newObject();
        ObjectNode body = client.newObject();
        String html = OutboundHtml.clean(message.html());
        boolean hasHtml = !html.isBlank();
        String text = textPartFor(message, html, hasHtml);

        if (hasHtml) {
            body.put("type", "multipart/alternative");
            ArrayNode parts = body.putArray("subParts");
            parts.addObject().put("partId", "t").put("type", "text/plain");
            parts.addObject().put("partId", "h").put("type", "text/html");
            values.putObject("t").put("value", text);
            values.putObject("h").put("value", html);
        } else {
            body.put("partId", "t").put("type", "text/plain");
            values.putObject("t").put("value", text);
        }

        List<Attachment> files = message.attachments();
        if (files.isEmpty()) {
            draft.set("bodyStructure", body);
        } else {
            // multipart/mixed wrapping the body part and then the files, which is the
            // structure every mail client has produced for thirty years and the only
            // one that keeps a plain text alternative next to the HTML rather than
            // demoting it to a sibling of the attachments.
            //
            // bodyStructure and the textBody/htmlBody/attachments shorthand are two
            // ways to say the same thing and JMAP forbids sending both, so building
            // the tree by hand here is not a longer way of doing something simpler.
            ObjectNode mixed = draft.putObject("bodyStructure");
            mixed.put("type", "multipart/mixed");
            ArrayNode parts = mixed.putArray("subParts");
            parts.add(body);
            for (Attachment file : files) {
                if (file == null || file.blobId() == null || file.blobId().isBlank()) {
                    throw new MailException(MailException.Kind.PROTOCOL,
                            "An attachment reached the send with no uploaded blob behind it");
                }
                ObjectNode part = parts.addObject();
                part.put("blobId", file.blobId());
                part.put("type", file.type() == null || file.type().isBlank()
                        ? "application/octet-stream" : file.type());
                // safeName and not the raw name: this ends up as a MIME filename
                // parameter on the recipient's side, and the same quoting and path
                // separator problems apply there as on our own download header.
                part.put("name", file.safeName());
                part.put("disposition", "attachment");
                // size is deliberately absent. RFC 8621 marks it server-set, and
                // sending it makes Stalwart reject the whole Email/set as invalid.
            }
        }
        draft.set("bodyValues", values);
        return draft;
    }

    /**
     * How long a References chain is allowed to get before it is trimmed.
     *
     * RFC 5322 says a client may drop entries from a long chain and names the rule:
     * keep the first, because that is what identifies the conversation, and drop from
     * the second onward. Twenty is generous for a mail thread and keeps the header
     * well under the point where a receiving server starts folding or refusing it.
     */
    private static final int MAX_REFERENCES = 20;

    private static List<String> threadReferences(Outgoing message) {
        List<String> parent = message.references();
        List<String> chain = new ArrayList<>(parent.size() + 1);
        chain.addAll(parent);
        if (!chain.contains(message.inReplyTo())) chain.add(message.inReplyTo());
        if (chain.size() <= MAX_REFERENCES) return chain;
        List<String> trimmed = new ArrayList<>(MAX_REFERENCES);
        trimmed.add(chain.get(0));
        trimmed.addAll(chain.subList(chain.size() - (MAX_REFERENCES - 1), chain.size()));
        return trimmed;
    }

    /**
     * The text alternative, supplied or derived, and never absent when there is HTML.
     *
     * A message carrying an HTML part and no plain part is one of the oldest signals a
     * spam filter has, because a filter cannot read what it cannot parse and a sender
     * who omits the readable half is usually hiding something in the other one. We
     * have SES production access and a domain reputation, so an avoidable spam signal
     * on every outgoing mail is a business cost and not a stylistic one. The derived
     * part comes from walking the sanitised HTML, and the SesSender fallback is only
     * reached when that walk produces nothing at all, which is the case of a body that
     * is one image and no words.
     */
    private String textPartFor(Outgoing message, String html, boolean hasHtml) {
        String supplied = message.text();
        if (supplied != null && !supplied.isBlank()) return supplied;
        if (!hasHtml) return "";
        String derived = OutboundHtml.toText(html);
        return derived.isBlank() ? ses.toPlainText(html) : derived;
    }

    private Identity defaultIdentity(String user) {
        Identity cached = identityCache.get(user);
        if (cached != null) return cached;

        List<Identity> identities = listIdentities(user);
        if (identities.isEmpty()) {
            throw new MailException(MailException.Kind.NOT_FOUND,
                    "Stalwart lists no send identity for " + user);
        }
        Identity chosen = identities.get(0);
        for (Identity i : identities) {
            if (i.email() != null && i.email().equalsIgnoreCase(user)) {
                chosen = i;
                break;
            }
        }
        identityCache.put(user, chosen);
        return chosen;
    }

    /**
     * The folder id behind each role, remembered for a few minutes.
     *
     * Sending used to cost three round trips before a single byte moved: one for the
     * identity list, one for the folder list, and one for the message itself. Two of
     * those three ask for things that do not change. A mailbox id in JMAP is stable
     * for the life of the mailbox and a send identity changes when an administrator
     * adds one, so re-reading both on every keystroke of a draft autosave is paying a
     * network round trip for an answer we already had. Only the id is cached and never
     * the message counts, because the counts are the part that goes stale in seconds.
     *
     * The staleness window is deliberately short rather than absent: an operator who
     * adds an identity or a folder in Stalwart's admin should see it take effect
     * without anybody restarting this application, and a few minutes is the longest
     * anyone would tolerate wondering whether it worked.
     */
    private Map<String, String> roleFolderIds(String user) {
        Cached<Map<String, String>> hit = roleCache.get(user);
        if (hit != null && !hit.isStale()) return hit.value();

        Map<String, String> byRole = new LinkedHashMap<>();
        for (MailFolder f : listFolders(user)) {
            if (f.role() != null && !f.role().isBlank() && f.id() != null) {
                byRole.put(f.role().toLowerCase(Locale.ROOT), f.id());
            }
        }
        Map<String, String> frozen = Map.copyOf(byRole);
        roleCache.put(user, new Cached<>(frozen, Instant.now().plusSeconds(CACHE_SECONDS)));
        return frozen;
    }

    /**
     * Drops what this service remembers about one mailbox.
     *
     * Locking a mailbox should reach this, so that the next person to open the same
     * address on the same machine starts from what the mail server says rather than
     * from what the previous session was told.
     */
    public void forgetCaches(String user) {
        roleCache.remove(user);
        identityCache.remove(user);
    }

    // ------------------------------------------------------------------
    // Several mailboxes at once
    // ------------------------------------------------------------------

    /**
     * The mailboxes overview wants unread counts for every shared address at once.
     * These really are independent round trips, so fan them out and let them all
     * wait together instead of adding five request times up. One mailbox being
     * unreachable returns an empty list for that address rather than blanking the
     * page, which is the whole reason this does not use a plain stream.
     *
     * Only covers addresses whose credential this process is currently holding.
     */
    public Map<String, List<MailFolder>> listFoldersForAll(Collection<String> users) {
        if (users == null || users.isEmpty()) return Map.of();
        Map<String, List<MailFolder>> out = new ConcurrentHashMap<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String user : users) {
                pool.execute(() -> {
                    try {
                        out.put(user, listFolders(user));
                    } catch (RuntimeException e) {
                        out.put(user, List.of());
                    }
                });
            }
        }
        return Map.copyOf(out);
    }

    // ------------------------------------------------------------------
    // JSON to records
    // ------------------------------------------------------------------

    private static MessageSummary toSummary(JsonNode e) {
        JsonNode keywords = e.path("keywords");
        return new MessageSummary(
                JmapClient.text(e, "id"),
                JmapClient.text(e, "threadId"),
                JmapClient.text(e, "subject"),
                addresses(e.path("from")), addresses(e.path("to")), addresses(e.path("cc")),
                JmapClient.text(e, "preview"),
                instant(e, "receivedAt"),
                e.path("size").asLong(0L),
                keywords.path("$seen").asBoolean(false),
                keywords.path("$flagged").asBoolean(false),
                keywords.path("$answered").asBoolean(false),
                keywords.path("$draft").asBoolean(false),
                e.path("hasAttachment").asBoolean(false),
                mailboxIds(e.path("mailboxIds")));
    }

    private static List<MailAddress> addresses(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<MailAddress> out = new ArrayList<>();
        for (JsonNode a : node) {
            out.add(new MailAddress(JmapClient.text(a, "name"), JmapClient.text(a, "email")));
        }
        return List.copyOf(out);
    }

    private static List<Attachment> attachments(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<Attachment> out = new ArrayList<>();
        for (JsonNode a : node) {
            out.add(new Attachment(
                    JmapClient.text(a, "partId"),
                    JmapClient.text(a, "blobId"),
                    JmapClient.text(a, "name"),
                    JmapClient.text(a, "type"),
                    a.path("size").asLong(0L),
                    JmapClient.text(a, "disposition"),
                    JmapClient.text(a, "cid")));
        }
        return List.copyOf(out);
    }

    /** mailboxIds arrives as {"a": true}. Only the true entries mean anything. */
    private static List<String> mailboxIds(JsonNode node) {
        if (!node.isObject()) return List.of();
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (entry.getValue().asBoolean(false)) ids.add(entry.getKey());
        }
        return List.copyOf(ids);
    }

    /**
     * Joins body parts to their values.
     *
     * The type check is load bearing. When a message has no HTML alternative,
     * Stalwart returns the same text/plain part under htmlBody as well, so a
     * client that trusts htmlBody blindly renders the plain text into an HTML
     * pane and shows the reader escaped markup or, worse, executes it.
     */
    private static String bodyOfType(JsonNode parts, JsonNode values, String wantedType) {
        if (!parts.isArray()) return null;
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : parts) {
            String type = JmapClient.text(part, "type");
            if (type == null || !type.equalsIgnoreCase(wantedType)) continue;
            String partId = JmapClient.text(part, "partId");
            if (partId == null) continue;
            String value = JmapClient.text(values.path(partId), "value");
            if (value != null) sb.append(value);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static List<String> strings(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode n : node) {
            String s = JmapClient.string(n);
            if (s != null) out.add(s);
        }
        return List.copyOf(out);
    }

    /** messageId comes back as an array of bare ids without angle brackets. */
    private static String firstString(JsonNode node) {
        List<String> all = strings(node);
        return all.isEmpty() ? JmapClient.string(node) : all.get(0);
    }

    /** JMAP timestamps are RFC 3339 in UTC, e.g. 2026-08-15T15:53:59Z. */
    private static Instant instant(JsonNode node, String field) {
        String raw = JmapClient.text(node, field);
        if (raw == null) return null;
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Input hygiene
    // ------------------------------------------------------------------

    private static List<String> cleanAddresses(List<String> raw) {
        if (raw == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String candidate : raw) {
            if (candidate == null) continue;
            String address = candidate.trim();
            if (address.isEmpty()) continue;
            // Same shape check the campaign side uses, so one bad row cannot get a
            // whole submission rejected by Stalwart with an opaque envelope error.
            if (!SesSender.EMAIL_OK.matcher(address).matches()) {
                throw new MailException(MailException.Kind.PROTOCOL, "Not an email address: " + address);
            }
            if (!out.contains(address)) out.add(address);
        }
        return out;
    }

    private static void addressArray(ArrayNode target, List<String> addresses) {
        for (String address : addresses) target.addObject().put("email", address);
    }

    /**
     * The UI says "seen", JMAP says "$seen". User labels have no dollar and must
     * keep their own name, so only the five system keywords get the prefix.
     */
    private static String normaliseKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            throw new MailException(MailException.Kind.PROTOCOL, "No keyword given");
        }
        String k = keyword.trim().toLowerCase(Locale.ROOT);
        // The keyword goes into a JSON Pointer patch path, where these two are structural.
        if (k.indexOf('/') >= 0 || k.indexOf('~') >= 0) {
            throw new MailException(MailException.Kind.PROTOCOL, "A keyword may not contain / or ~: " + keyword);
        }
        if (k.startsWith("$")) return k;
        return SYSTEM_KEYWORDS.contains(k) ? "$" + k : k;
    }
}
