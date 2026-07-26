package com.jarurat.mailer.models;

import jakarta.persistence.*;

@Entity
public class Template {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String templateAlias;
    private String subject;
    
    @Column(columnDefinition = "TEXT")
    private String htmlBody;

    public Template() {}
}