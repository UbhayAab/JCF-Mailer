package com.jarurat.mailer.services;

import com.jarurat.mailer.models.Contact;
import com.jarurat.mailer.models.DeliveryLog;
import com.jarurat.mailer.repositories.ContactRepository;
import com.jarurat.mailer.repositories.DeliveryLogRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class MailerService {

    private final ContactRepository contactRepository;
    private final DeliveryLogRepository deliveryLogRepository;
    private final SesV2Client sesClient;
    private final String fromEmail;
    
    // Virtual Thread Executor for high-throughput concurrency
    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public MailerService(
            ContactRepository contactRepository, 
            DeliveryLogRepository deliveryLogRepository,
            @Value("${aws.region}") String regionString,
            @Value("${aws.ses.fromEmail}") String fromEmail) {
            
        this.contactRepository = contactRepository;
        this.deliveryLogRepository = deliveryLogRepository;
        this.fromEmail = fromEmail;

        // Automatically uses the EC2 IAM Role!
        this.sesClient = SesV2Client.builder()
                .region(Region.of(regionString))
                .build();
    }

    public void runCampaignSimulation() {
        List<Contact> cleanContacts = contactRepository.findByStatus("CLEAN");
        System.out.println("🚀 Preparing to push " + cleanContacts.size() + " emails to AWS SES...");

        for (Contact contact : cleanContacts) {
            virtualThreadExecutor.submit(() -> {
                try {
                    sendEmailViaSes(contact);
                } catch (Exception e) {
                    System.err.println("❌ Failed to send to " + contact.getEmail() + ": " + e.getMessage());
                }
            });
        }
    }

    private void sendEmailViaSes(Contact contact) {
        // Construct the Email Content
        Content subject = Content.builder().data("Registration Confirmed: Horizon Oncology Summit 2026").build();
        Content body = Content.builder().data("Dear " + contact.getName() + ",\n\nThank you for registering for the Horizon Summit. Your secure webinar link is attached.\n\nBest,\nJarurat Care Foundation").build();
        Body messageBody = Body.builder().text(body).build();
        Message message = Message.builder().subject(subject).body(messageBody).build();

        // Specify the Recipient
        Destination destination = Destination.builder().toAddresses(contact.getEmail()).build();
        EmailContent emailContent = EmailContent.builder().simple(message).build();

        // Build the AWS API Request
        SendEmailRequest request = SendEmailRequest.builder()
                .fromEmailAddress(fromEmail)
                .destination(destination)
                .content(emailContent)
                .build();

        // Fire the request to AWS
        SendEmailResponse response = sesClient.sendEmail(request);

        // Log the successful dispatch in the database
        DeliveryLog log = new DeliveryLog(contact.getEmail(), "SENT_SES_MESSAGE_ID: " + response.messageId(), LocalDateTime.now());
        deliveryLogRepository.save(log);
        
        System.out.println("✅ AWS SES Dispatched: " + contact.getEmail() + " | Thread: " + Thread.currentThread());
    }
}