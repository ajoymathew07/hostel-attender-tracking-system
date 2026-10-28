package duty.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;

/**
 * Enforces the four layering rules with ArchUnit.
 *
 * <p>{@code failOnEmptyShould} stays on, so a rule that stops matching anything (e.g. after a rename)
 * fails instead of passing silently.
 */
@AnalyzeClasses(
        packages = LayeringRulesTest.ROOT,
        importOptions = ImportOption.DoNotIncludeTests.class)
class LayeringRulesTest {

    static final String ROOT = "duty";

    /** The modules that exist today. Add a module here when its first class is written. */
    private static final List<String> MODULES =
            List.of(
                    "ingestion",
                    "dispatch",
                    "workflow",
                    "ledger",
                    "identity",
                    "audit",
                    // Not in section 2.3's list. It stores what the manager may change without a
                    // redeploy, which section 4.5, section 6.2 (R5) and section 8.2 each require
                    // of a different module's numbers; see its package-info.
                    "settings",
                    "notification",
                    "analytics",
                    "reporting");

    private static final String SPRING_TRANSACTIONAL =
            "org.springframework.transaction.annotation.Transactional";
    private static final String JAKARTA_TRANSACTIONAL = "jakarta.transaction.Transactional";

    /**
     * Rule 1: {@code domain} depends only on the JDK, itself, and JPA annotations.
     *
     * <p>Written as an allowlist so any library nobody thought to name is also caught. JPA annotations are
     * allowed because entities live in the domain layer.
     */
    @ArchTest
    static final ArchRule domain_depends_only_on_the_jdk =
            classes()
                    .that()
                    .resideInAPackage("..domain..")
                    .should()
                    .onlyDependOnClassesThat()
                    .resideInAnyPackage(
                            "java..",
                            "jakarta.persistence..",
                            ROOT + "..domain..")
                    .because(
                            "the domain layer must stay framework-free apart from JPA mapping"
                                    + " annotations (architecture section 2.4, rule 1)");

    /** Rule 2: {@code web} never uses a repository; it goes through {@code application}. */
    @ArchTest
    static final ArchRule web_never_touches_a_repository =
            noClasses()
                    .that()
                    .resideInAPackage("..web..")
                    .should()
                    .dependOnClassesThat(isARepository().or(residesInInfrastructure()))
                    .because(
                            "controllers must go through an application service, which is where the"
                                    + " transaction boundary lives (architecture section 2.4,"
                                    + " rule 2)");

    /**
     * Rule 3: another module may only use a module's {@code application} package, never its
     * {@code domain}, {@code infrastructure} or {@code web}.
     */
    @ArchTest
    static void cross_module_calls_go_through_the_published_service_interface(JavaClasses classes) {
        for (String module : MODULES) {
            noClasses()
                    .that()
                    .resideOutsideOfPackage(ROOT + "." + module + "..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            ROOT + "." + module + ".domain..",
                            ROOT + "." + module + ".infrastructure..",
                            ROOT + "." + module + ".web..")
                    .because(
                            "only the published service interfaces in "
                                    + module
                                    + ".application may be used from outside the "
                                    + module
                                    + " module (architecture section 2.4, rule 3)")
                    .check(classes);
        }
    }

    /** Rule 4a — no class outside {@code application} carries {@code @Transactional}. */
    @ArchTest
    static final ArchRule transactional_classes_live_only_in_application =
            noClasses()
                    .that()
                    .resideOutsideOfPackage("..application..")
                    .should()
                    .beAnnotatedWith(SPRING_TRANSACTIONAL)
                    .orShould()
                    .beAnnotatedWith(JAKARTA_TRANSACTIONAL)
                    .as("no class outside an application package may be annotated @Transactional")
                    .because(
                            "transaction boundaries are where the concurrency guarantees of section"
                                    + " 7 are enforced, so they live in one predictable place"
                                    + " (architecture section 2.4, rule 4)");

    /** Rule 4b — nor does any method outside {@code application}. */
    @ArchTest
    static final ArchRule transactional_methods_live_only_in_application =
            noMethods()
                    .that()
                    .areDeclaredInClassesThat()
                    .resideOutsideOfPackage("..application..")
                    .should()
                    .beAnnotatedWith(SPRING_TRANSACTIONAL)
                    .orShould()
                    .beAnnotatedWith(JAKARTA_TRANSACTIONAL)
                    .as("no method outside an application package may be annotated @Transactional")
                    .because(
                            "transaction boundaries are where the concurrency guarantees of section"
                                    + " 7 are enforced, so they live in one predictable place"
                                    + " (architecture section 2.4, rule 4)");

    private static DescribedPredicate<JavaClass> isARepository() {
        return describe(
                "a Spring Data repository or a class named *Repository",
                javaClass ->
                        javaClass.getSimpleName().endsWith("Repository")
                                || javaClass.isAssignableTo(
                                        "org.springframework.data.repository.Repository"));
    }

    private static DescribedPredicate<JavaClass> residesInInfrastructure() {
        return describe(
                "resides in an infrastructure package",
                javaClass -> javaClass.getPackageName().contains(".infrastructure"));
    }
}
