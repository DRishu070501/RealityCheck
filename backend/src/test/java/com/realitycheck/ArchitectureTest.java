package com.realitycheck;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.data.repository.Repository;
import org.springframework.web.bind.annotation.RestController;

/**
 * Module boundaries from .kiro/steering/architecture.md. Rules about packages that do not exist
 * yet are allowed to match nothing, so they are in force from the first class added.
 */
@AnalyzeClasses(packages = "com.realitycheck", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ENGINE = "com.realitycheck.simulation.engine..";

    @ArchTest
    static final ArchRule engineIsPureJava = noClasses()
            .that().resideInAPackage(ENGINE)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "jakarta.persistence..",
                    "org.hibernate..",
                    "com.fasterxml.jackson..")
            .allowEmptyShould(true)
            .because("the simulation engine must be testable and reproducible without Spring, JPA or JSON");

    @ArchTest
    static final ArchRule engineDependsOnNoOtherModule = noClasses()
            .that().resideInAPackage(ENGINE)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.realitycheck.common..",
                    "com.realitycheck.factory..",
                    "com.realitycheck.workflow..",
                    "com.realitycheck.resource..",
                    "com.realitycheck.production..",
                    "com.realitycheck.scenario..",
                    "com.realitycheck.ai..",
                    "com.realitycheck.demo..")
            .allowEmptyShould(true)
            .because("the engine takes plain input records and returns plain result records");

    @ArchTest
    static final ArchRule simulationNeverCallsAi = noClasses()
            .that().resideInAPackage("com.realitycheck.simulation..")
            .should().dependOnClassesThat().resideInAPackage("com.realitycheck.ai..")
            .allowEmptyShould(true)
            .because("AI explains results; it must not be able to influence them");

    @ArchTest
    static final ArchRule scenarioNeverDependsOnSimulation = noClasses()
            .that().resideInAPackage("com.realitycheck.scenario..")
            .should().dependOnClassesThat().resideInAnyPackage("com.realitycheck.simulation..",
                    "com.realitycheck.ai..")
            .because("simulation depends on scenario (through ports declared in scenario), never the reverse");

    @ArchTest
    static final ArchRule operationalModulesKnowNothingOfScenarios = noClasses()
            .that().resideInAnyPackage(
                    "com.realitycheck.factory..",
                    "com.realitycheck.workflow..",
                    "com.realitycheck.resource..",
                    "com.realitycheck.production..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.realitycheck.scenario..",
                    "com.realitycheck.simulation..",
                    "com.realitycheck.ai..")
            .because("scenarios read live configuration and history; live data never depends on what-ifs");

    @ArchTest
    static final ArchRule simulationReadsOnlyThroughScenarioSnapshots = noClasses()
            .that().resideInAPackage("com.realitycheck.simulation..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.realitycheck.factory..",
                    "com.realitycheck.workflow..",
                    "com.realitycheck.resource..",
                    "com.realitycheck.production..")
            .because("a run reads only its stored snapshot, assembled by the scenario module");

    @ArchTest
    static final ArchRule commonDependsOnNoFeature = noClasses()
            .that().resideInAPackage("com.realitycheck.common..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.realitycheck.factory..",
                    "com.realitycheck.workflow..",
                    "com.realitycheck.resource..",
                    "com.realitycheck.production..",
                    "com.realitycheck.scenario..",
                    "com.realitycheck.simulation..",
                    "com.realitycheck.ai..",
                    "com.realitycheck.demo..");

    @ArchTest
    static final ArchRule controllersDoNotTouchRepositories = noClasses()
            .that().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat().areAssignableTo(Repository.class)
            .because("controllers delegate to services");
}
