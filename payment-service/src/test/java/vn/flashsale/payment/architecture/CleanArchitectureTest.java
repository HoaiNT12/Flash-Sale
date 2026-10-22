package vn.flashsale.payment.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "vn.flashsale.payment", importOptions = ImportOption.DoNotIncludeTests.class)
class CleanArchitectureTest {
    @ArchTest
    static final ArchRule innerLayersMustNotDependOnTransportOrInfrastructure = noClasses()
            .that().resideInAnyPackage("..domain..", "..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "vn.flashsale.common..", "jakarta..",
                    "java.sql..", "javax.sql..", "..infrastructure..")
            .because("business rules and use cases must remain independent of Spring, HTTP and database adapters");

    @ArchTest
    static final ArchRule domainMustNotDependOnApplication = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("..application..");
}
