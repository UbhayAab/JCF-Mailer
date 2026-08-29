package com.jarurat.mailer.services;

import com.jarurat.mailer.models.AuditLog;
import com.jarurat.mailer.repositories.AuditLogRepository;
import com.jarurat.mailer.security.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public void record(String action, String target, String detail) {
        auditLogRepository.save(new AuditLog(currentActor(), action, target, detail, currentIp()));
    }

    public static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth.getName() == null ? "system" : auth.getName();
    }

    /**
     * This used to read the first element of {@code X-Forwarded-For}, which is the
     * one the caller writes, so the source address on every audit row was a value
     * the audited party chose. An audit log that records what the subject asked it
     * to record is worse than one with no address column at all, because it will be
     * believed. {@link ClientIp} carries the reasoning and is the only reader now.
     */
    private String currentIp() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) return null;
        String ip = ClientIp.of(attrs.getRequest());
        return ip.isEmpty() ? null : ip;
    }
}
