package com.jarurat.mailer.mail;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A JmapClient that answers plausibly and keeps every request it was handed.
 *
 * These tests are about the JSON this application produces and not about what
 * Stalwart does with it, so the assertions have to be able to read the exact request
 * that would have gone over the wire. Mocking the four small JSON helpers rather than
 * stubbing whole method results is what makes that possible: MailService builds real
 * Jackson nodes through them, this class records the finished methodCalls array, and
 * a test then walks it looking for a property that must not exist.
 *
 * The canned answers are the minimum shape MailService reads back, which is why they
 * name a Drafts and a Sent mailbox and one identity and nothing else.
 */
final class FakeJmap {

    private final ObjectMapper json = new ObjectMapper();
    final JmapClient client = mock(JmapClient.class);

    /** Every methodCalls array this fake was asked to send, oldest first. */
    final List<ArrayNode> requests = new ArrayList<>();

    /** Set to a mailbox role that should be missing, to exercise the not-found paths. */
    String withoutRole = null;

    FakeJmap() {
        when(client.newObject()).thenAnswer(i -> json.createObjectNode());
        when(client.newArray()).thenAnswer(i -> json.createArrayNode());
        when(client.accountArgs(anyString()))
                .thenAnswer(i -> json.createObjectNode().put("accountId", "acc-1"));
        when(client.invocation(anyString(), any(), anyString())).thenAnswer(i -> {
            ArrayNode call = json.createArrayNode();
            call.add(i.getArgument(0, String.class));
            call.add((JsonNode) i.getArgument(1));
            call.add(i.getArgument(2, String.class));
            return call;
        });
        when(client.call(anyString(), anyList(), any())).thenAnswer(i -> {
            ArrayNode methodCalls = i.getArgument(2);
            requests.add(methodCalls);
            return answer(methodCalls);
        });
        // The real lookup, because matching on the name as well as the call id is the
        // behaviour MailService depends on and a stub that ignored it would hide a bug
        // in the caller rather than expose one.
        when(client.response(any(), anyString(), anyString())).thenAnswer(i -> {
            JsonNode responses = i.getArgument(0);
            String method = i.getArgument(1);
            String callId = i.getArgument(2);
            for (JsonNode entry : responses) {
                if (entry.size() >= 3 && callId.equals(s(entry.path(2)))
                        && method.equals(s(entry.path(0)))) {
                    return entry.path(1);
                }
            }
            throw new MailException(MailException.Kind.PROTOCOL, "no " + method + " for " + callId);
        });
    }

    /** The last request that carried a call to the named method. */
    ObjectNode argsFor(String method, String callId) {
        for (int r = requests.size() - 1; r >= 0; r--) {
            for (JsonNode call : requests.get(r)) {
                if (method.equals(s(call.path(0))) && callId.equals(s(call.path(2)))) {
                    return (ObjectNode) call.path(1);
                }
            }
        }
        throw new AssertionError("no request carried " + method + " as " + callId);
    }

    boolean sent(String method) {
        for (ArrayNode request : requests) {
            for (JsonNode call : request) {
                if (method.equals(s(call.path(0)))) return true;
            }
        }
        return false;
    }

    private static String s(JsonNode node) {
        return node.isString() ? node.asString() : "";
    }

    private ArrayNode answer(ArrayNode methodCalls) {
        ArrayNode out = json.createArrayNode();
        for (JsonNode call : methodCalls) {
            String method = s(call.path(0));
            String callId = s(call.path(2));
            ObjectNode args = json.createObjectNode();
            switch (method) {
                case "Identity/get" -> args.putArray("list").addObject()
                        .put("id", "identity-1").put("name", "Priya Sharma")
                        .put("email", "priya@jarurat.care");
                case "Mailbox/get" -> {
                    ArrayNode list = args.putArray("list");
                    for (String role : List.of("inbox", "drafts", "sent", "trash")) {
                        if (role.equals(withoutRole)) continue;
                        list.addObject().put("id", "mb-" + role).put("name", role).put("role", role);
                    }
                }
                case "Email/set" -> {
                    args.putObject("created").putObject("draft").put("id", "email-new");
                    args.putObject("updated");
                    args.putArray("destroyed");
                }
                case "EmailSubmission/set" ->
                        args.putObject("created").putObject("sub").put("id", "submission-1");
                default -> args.putArray("list");
            }
            ArrayNode entry = json.createArrayNode();
            entry.add(method);
            entry.add(args);
            entry.add(callId);
            out.add(entry);
        }
        return out;
    }
}
