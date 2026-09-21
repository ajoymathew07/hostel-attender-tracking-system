package in.ac.iitm.hostel.duty.categorization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * One entry in the classifier lexicon, editable by the manager without a redeploy (data model
 * section 3.7, section 5.5).
 *
 * <p>{@code domain} is a plain string rather than an enum: the complaint domain vocabulary is owned
 * by the workflow module, and a categorization entity may not reference another module's domain
 * types (architecture section 2.4, rule 3). The column carries no CHECK constraint in the design.
 *
 * <p>Retiring a term sets {@code active = false} rather than deleting the row, so a past
 * classification stays explainable (data model section 3.1, principle 4).
 */
@Entity
@Table(name = "category_term")
public class CategoryTerm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "domain", nullable = false, length = 16)
    private String domain;

    @Column(name = "term", nullable = false, length = 64)
    private String term;

    @Column(name = "weight", nullable = false, precision = 4, scale = 2)
    private BigDecimal weight;

    /** Whether Jaro–Winkler fuzzy matching applies, for transliterated text (test plan E14). */
    @Column(name = "fuzzy", nullable = false)
    private boolean fuzzy;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** For JPA only. */
    protected CategoryTerm() {}

    public CategoryTerm(String domain, String term, BigDecimal weight, Instant createdAt) {
        this.domain = Objects.requireNonNull(domain, "domain");
        this.term = normalizeTerm(term);
        this.weight = Objects.requireNonNull(weight, "weight");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.fuzzy = true;
        this.active = true;
    }

    /** {@code uq_term} is on the stored form, so matching must normalise before it stores. */
    public static String normalizeTerm(String term) {
        return Objects.requireNonNull(term, "term").trim().toLowerCase(Locale.ROOT);
    }

    public void reweight(BigDecimal newWeight) {
        this.weight = Objects.requireNonNull(newWeight, "weight");
    }

    public void setFuzzyMatching(boolean enabled) {
        this.fuzzy = enabled;
    }

    /** Tombstone rather than delete, so past classifications stay explainable. */
    public void retire() {
        this.active = false;
    }

    public void reinstate() {
        this.active = true;
    }

    public Long getId() {
        return id;
    }

    public String getDomain() {
        return domain;
    }

    public String getTerm() {
        return term;
    }

    public BigDecimal getWeight() {
        return weight;
    }

    public boolean isFuzzy() {
        return fuzzy;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
