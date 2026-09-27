package in.ac.iitm.hostel.duty.workflow.web;

import in.ac.iitm.hostel.duty.workflow.domain.ComplaintDomain;
import java.util.Map;

/**
 * The domain badge's glyph, for both screens (section 10.5).
 *
 * <p>Colour is never the only signal, so a domain carries an icon and a text label as well as a
 * hue — which also covers colour-blind users, and is the difference between "recognisable at a
 * glance" and "readable if you stop and read" for the attender standing in a corridor (section
 * 10.2, rule 3).
 *
 * <p>The glyph is {@code aria-hidden} in both templates, so a screen reader announces the label
 * alone rather than an emoji's name.
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
