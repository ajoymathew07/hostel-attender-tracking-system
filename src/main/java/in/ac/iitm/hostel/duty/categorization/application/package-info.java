/**
 * Application layer of the {@code categorization} module: services, orchestration and transaction
 * boundaries.
 *
 * {@code @Transactional} lives here and nowhere else (architecture section 2.4, rule 4). The
 * service interfaces declared in this package are the module's published surface: other modules
 * depend on these and on nothing else of {@code categorization} (architecture section 2.4, rule 3).
 */
package in.ac.iitm.hostel.duty.categorization.application;
