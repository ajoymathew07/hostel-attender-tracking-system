package duty.workflow.web;

import duty.workflow.domain.ComplaintDomain;
import java.util.Map;

/**
 * The icon shown on each domain badge, so colour is never the only signal. Hidden from screen readers,
 * which read the text label.
 */
final class DomainGlyphs {

    static final Map<String, String> BY_DOMAIN =
            Map.of(
                    ComplaintDomain.ELECTRICAL.name(), "⚡",
                    ComplaintDomain.CIVIL.name(), "🔧",
                    ComplaintDomain.RO_PLANT.name(), "💧",
                    ComplaintDomain.NETWORK.name(), "🌐",
                    ComplaintDomain.UNCLASSIFIED.name(), "❓");

    private DomainGlyphs() {}
}
