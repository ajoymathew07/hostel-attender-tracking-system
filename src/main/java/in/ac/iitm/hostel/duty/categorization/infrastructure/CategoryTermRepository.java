package in.ac.iitm.hostel.duty.categorization.infrastructure;

import in.ac.iitm.hostel.duty.categorization.domain.CategoryTerm;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** The classifier lexicon (data model section 3.7). */
public interface CategoryTermRepository extends JpaRepository<CategoryTerm, Long> {

    List<CategoryTerm> findByActiveIsTrue();

    List<CategoryTerm> findByDomainAndActiveIsTrue(String domain);

    Optional<CategoryTerm> findByDomainAndTerm(String domain, String term);
}
