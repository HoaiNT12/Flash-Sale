package vn.flashsale.inventory;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "vn.flashsale.inventory", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest
    static final ArchRule innerLayersDoNotDependOnInfrastructure = noClasses()
            .that().resideInAnyPackage("vn.flashsale.inventory.domain..", "vn.flashsale.inventory.application..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "java.sql..", "javax.sql..",
                    "vn.flashsale.common..", "vn.flashsale.inventory.adapter..", "vn.flashsale.inventory.configuration..");
}
