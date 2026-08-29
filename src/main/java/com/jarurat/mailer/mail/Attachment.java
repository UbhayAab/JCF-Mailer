package com.jarurat.mailer.mail;

import java.util.Locale;
import java.util.Set;

/**
 * One part of a message that is not body text. The bytes are not carried here:
 * fetch them with JmapClient.download(user, blobId, name, type) when the user
 * actually asks for them, so opening a message never drags megabytes into heap.
 *
 * The same record describes a file on its way out. A part the compose screen has
 * just uploaded is a blob id, a name, a type and a size, which is exactly what a
 * part on the way in is, so the send path builds one of these rather than a
 * parallel type that would drift away from this one.
 */
public record Attachment(String partId, String blobId, String name, String type,
                         long size, String disposition, String cid) {

    /**
     * Extensions this mailer will not carry, whichever direction they are going.
     *
     * The first nine are the set every serious provider refuses outright, because
     * a double click on any of them runs code on the recipient's machine: exe,
     * scr, bat, cmd, com, pif, js, vbs and jar. The rest are the same weapon with
     * a different handle - Windows Script Host, PowerShell, installer, help and
     * shortcut formats that are equally a single double click away from running,
     * and iso, which is how a Windows-executable payload gets mailed as "a disc
     * image" and mounts itself on arrival.
     *
     * Deliberately not on the list: pdf, doc, docx, xls, xlsx and zip. They can
     * all carry something hostile, and refusing them would make this mailer
     * useless to the people it is for. An archive is not opened and looked inside
     * either, which is a known gap and a stated one.
     *
     * The declared MIME type is not what is checked, because it is whatever the
     * sending browser felt like and the recipient's computer decides what to do
     * with the file by its name, not by a header that no longer exists once the
     * file is saved.
     */
    private static final Set<String> REFUSED_EXTENSIONS = Set.of(
            "exe", "scr", "bat", "cmd", "com", "pif", "js", "vbs", "jar",
            "jse", "vbe", "ws", "wsf", "wsh", "wsc", "sct",
            "ps1", "ps1xml", "psc1", "msh", "msi", "msp", "mst",
            "hta", "cpl", "msc", "lnk", "scf", "reg", "chm", "hlp",
            "ade", "adp", "ins", "isp", "its", "ksh", "csh", "sh",
            "dll", "ocx", "sys", "drv", "gadget", "application", "appref-ms", "iso");

    /** A part built for sending: no partId, because the bytes live in a blob and not in the message. */
    public static Attachment outgoing(String blobId, String name, String type, long size) {
        return new Attachment(null, blobId, name, type, size, "attachment", null);
    }

    /**
     * The refused extension in this filename, lowercased and without its dot, or
     * null when the name is one we will carry.
     *
     * Only the final extension counts. Checking every dot separated piece would
     * refuse "www.example.com.pdf" over the "com" in the middle of a hostname,
     * and a file whose real last extension is pdf is opened by a PDF reader
     * however many dots came before it. "report.pdf.exe" is caught because exe is
     * still the last one.
     *
     * Trailing dots and spaces are stripped first because Windows strips them
     * when it resolves a path, so "payload.exe." and "payload.exe " both save and
     * run as payload.exe.
     */
    public static String refusedExtension(String filename) {
        if (filename == null) return null;

        String base = filename.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);

        int end = base.length();
        while (end > 0 && (base.charAt(end - 1) == '.' || base.charAt(end - 1) == ' ')) end--;
        base = base.substring(0, end);

        int dot = base.lastIndexOf('.');
        if (dot < 0 || dot == base.length() - 1) return null;

        String ext = base.substring(dot + 1).toLowerCase(Locale.ROOT);
        return REFUSED_EXTENSIONS.contains(ext) ? ext : null;
    }

    /**
     * Inline parts are images the HTML body references with a cid: URL. They are
     * attachments as far as JMAP is concerned but must not be listed as files.
     */
    public boolean isInline() {
        return (cid != null && !cid.isBlank()) || "inline".equalsIgnoreCase(disposition);
    }

    /**
     * Never null, and never anything the sender chose verbatim.
     *
     * This lands in a Content-Disposition header, so the raw MIME filename is
     * attacker controlled input on a response header: a quote or a CR lets the
     * sender close the parameter or split the header, and path separators let
     * them suggest a filename that escapes the download folder. Only a plain
     * basename survives.
     */
    public String safeName() {
        if (name == null || name.isBlank()) return "attachment";

        String base = name.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);

        StringBuilder out = new StringBuilder(base.length());
        for (int i = 0; i < base.length() && out.length() < 120; i++) {
            char c = base.charAt(i);
            // Letters, digits and a short punctuation set. Everything else, including
            // quotes, semicolons, control characters and CR/LF, is replaced.
            out.append(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' || c == ' ' ? c : '_');
        }

        String cleaned = out.toString().trim();
        // A name of only dots would still address a directory.
        return cleaned.isEmpty() || cleaned.chars().allMatch(c -> c == '.') ? "attachment" : cleaned;
    }
}
