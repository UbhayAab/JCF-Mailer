package com.jarurat.mailer.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One message on its way out, whether it is being sent now or only saved as a draft.
 *
 * This exists as a record rather than as five more parameters on MailService.send
 * because the send path had already reached seven arguments and was about to take
 * four more, and a call site with eleven positional arguments is a place where two
 * lists of addresses get swapped and nobody notices until a blind copy arrives in the
 * To line. Naming every field also means the draft path and the send path provably
 * carry the same shape, which is the whole point of drafts: what you resume on a
 * laptop has to be what you were writing on a phone.
 *
 * Every list is copied and never null, so a caller cannot mutate a message after the
 * service has started building JSON from it.
 */
public record Outgoing(List<String> to, List<String> cc, List<String> bcc,
                       String subject, String html, String text,
                       List<Attachment> attachments,
                       String inReplyTo, List<String> references) {

    /**
     * How many addresses one message may carry across To, Cc and Bcc together.
     *
     * This is a mailbox and not a campaign tool. Campaign Studio exists for the list
     * of four hundred donors, it throttles, it suppresses bounces and it keeps a
     * per-recipient record, and a person pasting four hundred addresses into a compose
     * sheet gets none of that and puts the sending domain's reputation on one careless
     * paste. Fifty covers every real one-to-many mail a fifteen person foundation
     * sends, and anything past it is a question the sender should be asked rather than
     * a limit they should discover from a bounce.
     */
    public static final int MAX_RECIPIENTS = 50;

    public Outgoing {
        to = copy(to);
        cc = copy(cc);
        bcc = copy(bcc);
        references = copy(references);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        subject = subject == null ? "" : subject;
        inReplyTo = blankToNull(inReplyTo);
    }

    /** The plainest message there is, and the shape almost every caller wants. */
    public static Outgoing message(List<String> to, List<String> cc, String subject,
                                   String html, String text) {
        return new Outgoing(to, cc, List.of(), subject, html, text, List.of(), null, List.of());
    }

    public Outgoing withBcc(List<String> blind) {
        return new Outgoing(to, cc, blind, subject, html, text, attachments, inReplyTo, references);
    }

    public Outgoing withAttachments(List<Attachment> files) {
        return new Outgoing(to, cc, bcc, subject, html, text, files, inReplyTo, references);
    }

    /**
     * The two headers that make a reply a reply.
     *
     * Both are bare message ids without the angle brackets, because that is the form
     * RFC 8621 puts on the wire and Stalwart writes the brackets itself. Handing it a
     * value that already has them produces a header with two sets of brackets, which
     * every threading implementation then fails to match.
     */
    public Outgoing inThread(String parentMessageId, List<String> parentReferences) {
        return new Outgoing(to, cc, bcc, subject, html, text, attachments,
                parentMessageId, parentReferences);
    }

    /** Everyone the envelope has to name, deduplicated, in To then Cc then Bcc order. */
    public List<String> everyRecipient() {
        List<String> all = new ArrayList<>(to.size() + cc.size() + bcc.size());
        addAllNew(all, to);
        addAllNew(all, cc);
        addAllNew(all, bcc);
        return List.copyOf(all);
    }

    public boolean hasHtml() {
        return html != null && !html.isBlank();
    }

    public boolean isThreaded() {
        return inReplyTo != null;
    }

    /**
     * An address in both To and Bcc is one delivery and not two.
     *
     * Without this the envelope names the same person twice and their server either
     * delivers the message twice or rejects the second copy, and the sender sees a
     * bounce for a message that arrived. Matching is case insensitive because a domain
     * is, and To wins over Cc which wins over Bcc, so the visible placement is the one
     * that survives.
     */
    private static void addAllNew(List<String> into, List<String> more) {
        for (String address : more) {
            boolean seen = false;
            for (String already : into) {
                if (already.equalsIgnoreCase(address)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) into.add(address);
        }
    }

    private static List<String> copy(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            if (s != null && !s.isBlank()) out.add(s.trim());
        }
        return List.copyOf(out);
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** Lowercases the domain and leaves the local part alone, which is what RFC 5321 allows. */
    public static String normalise(String address) {
        if (address == null) return null;
        String a = address.trim();
        int lt = a.lastIndexOf('<');
        int gt = a.lastIndexOf('>');
        // "Priya Sharma <priya@jarurat.care>" is what a paste out of another client
        // looks like, and the address is the part inside the brackets.
        if (lt >= 0 && gt > lt) a = a.substring(lt + 1, gt).trim();
        int at = a.lastIndexOf('@');
        if (at < 0) return a;
        return a.substring(0, at) + "@" + a.substring(at + 1).toLowerCase(Locale.ROOT);
    }
}
