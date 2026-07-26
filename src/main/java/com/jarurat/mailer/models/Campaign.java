package com.jarurat.mailer.models;

import jakarta.persistence.*;

@Entity
public class Campaign {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String campaignName;
    private String status; // e.g., "DRAFT", "SENDING", "COMPLETED"

    public Campaign() {}

    public Campaign(String campaignName, String status) {
        this.campaignName = campaignName;
        this.status = status;
    }
}