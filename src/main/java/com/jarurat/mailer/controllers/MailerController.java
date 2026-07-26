package com.jarurat.mailer.controllers;

import com.jarurat.mailer.services.MailerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mailer")
public class MailerController {

    private final MailerService mailerService;

    public MailerController(MailerService mailerService) {
        this.mailerService = mailerService;
    }

    @GetMapping("/trigger-campaign")
    public ResponseEntity<String> triggerCampaign() {
        System.out.println("🌐 Web request received! Triggering campaign...");
        mailerService.runCampaignSimulation();
        return ResponseEntity.ok("Campaign triggered successfully! Check your terminal.");
    }

    // NEW: The AWS SNS Webhook Endpoint
    @PostMapping("/sns-webhook")
    public ResponseEntity<String> handleSnsWebhook(
            @RequestHeader(value = "x-amz-sns-message-type", required = false) String messageType,
            @RequestBody String payload) {

        System.out.println("🚨 AWS SNS WEBHOOK HIT!");
        
        // AWS SNS sends a "SubscriptionConfirmation" message the very first time it connects.
        // We have to parse this and visit the URL they provide to prove we own this server.
        if ("SubscriptionConfirmation".equals(messageType)) {
            System.out.println("AWS is asking to confirm the subscription. Payload:");
            System.out.println(payload);
            // In Phase 4, we will write the code to automatically visit the SubscribeURL.
        } 
        // This handles the actual Bounces and Complaints
        else if ("Notification".equals(messageType)) {
            System.out.println("AWS sent a Bounce or Complaint notification. Payload:");
            System.out.println(payload);
            // In Phase 4, we will extract the email address and update the database status to SUPPRESSED.
        }

        // We must return 200 OK, otherwise AWS will think our server is down and keep retrying.
        return ResponseEntity.ok("Webhook received and logged");
    }
}