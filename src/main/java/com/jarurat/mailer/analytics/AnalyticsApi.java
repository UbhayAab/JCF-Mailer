package com.jarurat.mailer.analytics;

import com.jarurat.mailer.services.AuditService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jarurat.mailer.controllers.AudienceApi.csv;

/**
 * Read side of the honest numbers. Every response is scoped by a day window and
 * optionally by campaign, and every rate in them comes from HUMAN events only.
 *
 * Three endpoints were removed here rather than wired: /summary, /clients and
 * /classifier each returned a value that /overview already returns under a key of
 * the same name, and /overview is what the console actually calls. They were not
 * dark features waiting for a screen, they were a second door onto data already
 * coming through the first, and every one of them cost a permission check, a URL
 * and a shape to keep in step with the bundle. What was genuinely unreachable was
 * never the endpoint, it was two fields inside /overview that no renderer read:
 * clients.devices, and the whole classifier bucket table.
 */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsApi {

    private final AnalyticsService analytics;
    private final OpenTrackingService tracking;
    private final AuditService audit;
    private final SegmentService segments;
    private final SegmentRepository segmentRepository;

    public AnalyticsApi(AnalyticsService analytics, OpenTrackingService tracking, AuditService audit,
                        SegmentService segments, SegmentRepository segmentRepository) {
        this.analytics = analytics;
        this.tracking = tracking;
        this.audit = audit;
        this.segments = segments;
        this.segmentRepository = segmentRepository;
    }

    /**
     * The daily line on its own, and the window it covers alongside it.
     *
     * The window is why this exists. The dashboard sparkline needs one series and
     * one date range, and it gets them today by calling /overview, which runs the
     * summary, the link table, the client split and the classifier as well: five
     * passes over tracking_event to draw one line. It could never call this
     * endpoint instead, because the old shape was a bare list of days with no
     * from and no to on it, and the range printed above the chart comes from
     * summary.from. Echoing the window here is the whole difference between an
     * endpoint nobody can use and the right call for that card.
     */
    @GetMapping("/series")
    @PreAuthorize("hasAuthority('ANALYTICS_READ')")
    public Map<String, Object> series(@RequestParam(required = false) Long campaignId,
                                      @RequestParam(defaultValue = "30") int days) {
        // Recomputed rather than derived from the rows: this is the same call
        // dailySeries makes internally, so the two cannot drift, and the strings
        // match summary.from and summary.to exactly for a caller reading both.
        AnalyticsService.Window w = AnalyticsService.Window.ofDays(days);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("campaignId", campaignId);
        out.put("windowDays", w.days());
        out.put("from", w.from().toString());
        out.put("to", w.to().toString());
        out.put("series", analytics.dailySeries(campaignId, days));
        return out;
    }

    /**
     * Kept, though nothing calls it yet, because a named consumer is queued for it:
     * the composer's own link table reads raw click rows with no bot filtering at
     * all, and the plan is to point it here and delete that path. Deleting this
     * would only mean writing it again.
     */
    @GetMapping("/links")
    @PreAuthorize("hasAuthority('ANALYTICS_READ')")
    public List<Map<String, Object>> links(@RequestParam(required = false) Long campaignId,
                                           @RequestParam(defaultValue = "30") int days,
                                           @RequestParam(defaultValue = "10") int limit) {
        return analytics.topLinks(campaignId, days, limit);
    }

    /**
     * Campaign against campaign, which is the one comparison the product does not
     * have anywhere else, and the reason this is not folded into /overview:
     * byCampaign runs five aggregate queries per campaign, so bundling it would
     * charge the whole cost of the account's history to every open of the screen.
     */
    @GetMapping("/campaigns")
    @PreAuthorize("hasAuthority('ANALYTICS_READ')")
    public Map<String, Object> campaigns(@RequestParam(defaultValue = "30") int days,
                                         @RequestParam(defaultValue = "12") int limit) {
        AnalyticsService.Window w = AnalyticsService.Window.ofDays(days);
        List<Map<String, Object>> rows = new ArrayList<>(analytics.byCampaign(days));

        // Biggest send first. The service hands back whatever order the group by
        // produced, which is effectively campaign id, so a chart drawn straight
        // off it ranks by age and by nothing a reader came to find out.
        rows.sort((a, b) -> Long.compare(asLong(b.get("sent")), asLong(a.get("sent"))));

        // A comparison with two hundred rows is not a comparison. Capping here
        // rather than in the renderer is what lets the response say how many rows
        // it left out, so the screen can print that instead of quietly lying.
        int cap = Math.max(1, Math.min(100, limit));
        boolean truncated = rows.size() > cap;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowDays", w.days());
        out.put("from", w.from().toString());
        out.put("to", w.to().toString());
        out.put("totalCampaigns", rows.size());
        out.put("truncated", truncated);
        out.put("campaigns", truncated ? new ArrayList<>(rows.subList(0, cap)) : rows);
        return out;
    }

    /** Sorting a Map<String, Object> whose values arrive boxed as whatever the driver chose. */
    private static long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    /**
     * One call for the whole Analytics screen, so opening it is not five round trips.
     *
     * Two of these keys are paid for on every request and read by nobody:
     * clients.devices, which OpenTrackingService fills in on every tracked open,
     * and classifier, whose bucket table is the honest per classification split.
     * The console draws the client half of clients and writes the classifier line
     * as prose off summary instead. charts.js now carries renderers for both;
     * mounting them is a console.html and console.js change.
     */
    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('ANALYTICS_READ')")
    public Map<String, Object> overview(@RequestParam(required = false) Long campaignId,
                                        @RequestParam(defaultValue = "30") int days) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", analytics.summary(campaignId, days));
        out.put("series", analytics.dailySeries(campaignId, days));
        out.put("links", analytics.topLinks(campaignId, days, 10));
        out.put("clients", analytics.clients(campaignId, days));
        out.put("classifier", analytics.classifierInfo(campaignId, days));
        return out;
    }

    // ------------------------------------------------------------------
    // Engagement segments
    // ------------------------------------------------------------------

    /** Who clicked, who opened and did nothing, who cannot be known either way. */
    @GetMapping("/segments")
    @PreAuthorize("hasAuthority('ANALYTICS_READ')")
    public Map<String, Object> segments(@RequestParam Long campaignId) {
        return segments.summary(campaignId);
    }

    /** The people behind one number, which is the whole point of showing the number. */
    @GetMapping("/segment/people")
    @PreAuthorize("hasAuthority('ANALYTICS_READ')")
    public ResponseEntity<?> segmentPeople(@RequestParam Long campaignId,
                                           @RequestParam String segment,
                                           @RequestParam(defaultValue = "") String q,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int size) {
        try {
            EngagementSegment target = EngagementSegment.parse(segment);
            var found = segmentRepository.listIn(campaignId, target.name(), q,
                    org.springframework.data.domain.PageRequest.of(
                            Math.max(0, page), Math.min(200, Math.max(1, size))));

            List<Map<String, Object>> rows = new ArrayList<>();
            for (var r : found.getContent()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("email", r.getEmail());
                row.put("name", r.getName());
                row.put("status", r.getStatus());
                row.put("opens", r.getOpenCount());
                row.put("clicks", r.getClickCount());
                row.put("sentAt", r.getSentAt() == null ? "" : r.getSentAt().toString());
                rows.add(row);
            }
            return ResponseEntity.ok(Map.of("rows", rows, "page", found.getNumber(),
                    "totalPages", found.getTotalPages(), "totalElements", found.getTotalElements(),
                    "label", target.getLabel(), "description", target.getDescription()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @GetMapping("/segment/export")
    @PreAuthorize("hasAuthority('ANALYTICS_READ')")
    public void exportSegment(@RequestParam Long campaignId, @RequestParam String segment,
                              HttpServletResponse response) throws Exception {
        EngagementSegment target = EngagementSegment.parse(segment);
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"campaign-" + campaignId
                + "-" + target.name().toLowerCase(java.util.Locale.ROOT) + ".csv\"");

        try (PrintWriter writer = response.getWriter()) {
            writer.println("Email,Name,Group,Status,Opens,Clicks,Sent at");
            for (var r : segments.people(campaignId, target)) {
                writer.println(String.join(",",
                        csv(r.getEmail()), csv(r.getName()), csv(target.getLabel()), csv(r.getStatus()),
                        csv(String.valueOf(r.getOpenCount())), csv(String.valueOf(r.getClickCount())),
                        csv(r.getSentAt() == null ? "" : r.getSentAt().toString())));
            }
        }
    }

    /**
     * The action that makes analytics operational: a segment becomes an audience the
     * ordinary composer can mail. Needs LISTS_WRITE rather than ANALYTICS_READ, because
     * building an audience is a different kind of act from reading a report.
     */
    @PostMapping("/segment/save-as-list")
    @PreAuthorize("hasAuthority('LISTS_WRITE')")
    public ResponseEntity<?> saveSegmentAsList(@RequestParam Long campaignId,
                                               @RequestParam String segment,
                                               @RequestParam(required = false) String name) {
        try {
            Map<String, Object> result = segments.saveAsList(
                    campaignId, EngagementSegment.parse(segment), name);
            audit.record("SEGMENT_SAVED_AS_LIST", String.valueOf(result.get("name")),
                    result.get("added") + " people from campaign " + campaignId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /** When this audience reads its mail. A real behaviour, and not a reading figure. */
    @GetMapping("/time-to-open")
    @PreAuthorize("hasAuthority('ANALYTICS_READ')")
    public Map<String, Object> timeToOpen(@RequestParam Long campaignId) {
        return segments.timeToOpen(campaignId);
    }

    /**
     * Re-runs the classifier over stored history. Guarded on SETTINGS_WRITE rather than
     * ANALYTICS_READ because it rewrites numbers that have already been reported, and
     * audited for the same reason. A method level rule replaces any class level one, so
     * this endpoint deliberately does not also require ANALYTICS_READ.
     */
    @PostMapping("/reclassify")
    @PreAuthorize("hasAuthority('SETTINGS_WRITE')")
    public ResponseEntity<?> reclassify(@RequestParam(defaultValue = "50000") int max) {
        int changed = tracking.reclassify(max);
        audit.record("ANALYTICS_RECLASSIFIED", "tracking_event", changed + " verdict(s) rewritten");
        return ResponseEntity.ok(Map.of(
                "message", changed + " event(s) reclassified.",
                "changed", changed));
    }
}
