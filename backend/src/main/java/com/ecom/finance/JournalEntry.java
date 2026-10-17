package com.ecom.finance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Immutable;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/** Immutable double-entry journal entry. Mistakes are corrected with a new reversing entry, never by editing. */
@Entity
@Immutable
@Table(name = "journal_entries")
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "entry_number", nullable = false, unique = true, length = 20)
    private String entryNumber;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    @Column(nullable = false, length = 300)
    private String description;

    @Column(name = "source_type", nullable = false, length = 50)
    private String sourceType;

    @Column(name = "source_id")
    private UUID sourceId;

    @Column(name = "created_by")
    private UUID createdBy;

    @OneToMany(mappedBy = "entry", cascade = CascadeType.PERSIST)
    @BatchSize(size = 50)
    private List<JournalLine> lines = new ArrayList<>();

    protected JournalEntry() {
    }

    JournalEntry(String entryNumber, String description, String sourceType, UUID sourceId, UUID createdBy) {
        this.entryNumber = entryNumber;
        this.postedAt = Instant.now();
        this.description = description.length() > 300 ? description.substring(0, 300) : description;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.createdBy = createdBy;
    }

    void addLine(String accountCode, long debitMinor, long creditMinor) {
        lines.add(new JournalLine(this, accountCode, debitMinor, creditMinor));
    }

    public UUID getId() { return id; }
    public String getEntryNumber() { return entryNumber; }
    public Instant getPostedAt() { return postedAt; }
    public String getDescription() { return description; }
    public String getSourceType() { return sourceType; }
    public UUID getSourceId() { return sourceId; }
    public UUID getCreatedBy() { return createdBy; }
    public List<JournalLine> getLines() { return lines; }
}
