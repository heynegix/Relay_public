package com.example.relay.pcgateway

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.BeforeClass
import org.junit.Test

/**
 * Architecture rules enforcing the Relay security boundaries as compile-time-adjacent
 * facts, not just documentation. This test module's classpath contains every JVM
 * production module (relay-protocol, shared jvm, pc-gateway, broker via
 * testImplementation), so one import covers them all. Android-only code (app,
 * composeApp) is out of scope here.
 *
 * Each rule encodes an invariant that existing text contracts
 * (scripts/verify-implementation-contracts.ps1) cannot check because they only
 * grep for strings, not for actual dependency edges.
 */
class ArchitectureTest {

    companion object {
        private lateinit var productionClasses: JavaClasses

        @JvmStatic
        @BeforeClass
        fun importProductionClasses() {
            productionClasses = ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.example.relay")
        }
    }

    /**
     * relay-protocol defines the gateway trust contract and must stay pure: only
     * kotlin/java platform types and kotlinx.serialization. No Ktor, no SQL, no
     * other Relay module - otherwise the contract could drift with transport code.
     */
    @Test
    fun relayProtocolStaysPure() {
        classes()
            .that().resideInAPackage("com.example.relay.gateway.protocol..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                "com.example.relay.gateway.protocol..",
                "java..",
                "kotlin..",
                "kotlinx.serialization..",
                // Compiler-emitted nullability metadata, not a real dependency.
                "org.jetbrains.annotations..",
            )
            .because("relay-protocol is the transport-independent trust contract")
            .check(productionClasses)
    }

    /**
     * The Broker relays opaque envelopes and must never hold decryption capability.
     * javax.crypto (Cipher/KeyGenerator/Mac/...) in the broker would silently break
     * the "Broker never decrypts" invariant documented in BrokerStore.
     */
    @Test
    fun brokerNeverTouchesJavaxCrypto() {
        noClasses()
            .that().resideInAPackage("com.example.relay.broker..")
            .should().dependOnClassesThat().resideInAPackage("javax.crypto..")
            .because("the Broker must never be able to decrypt rescue envelopes")
            .check(productionClasses)
    }

    /**
     * Encryption/decryption primitives (javax.crypto.Cipher) are confined to the
     * rescue cryptography boundary. HMAC (Mac) and password KDF (SecretKeyFactory)
     * are legitimately used by the Gateway, so only Cipher is restricted.
     */
    @Test
    fun cipherUsageConfinedToRescueCryptoBoundary() {
        noClasses()
            .that().resideOutsideOfPackage("com.example.relay.rescue..")
            .should().dependOnClassesThat().haveFullyQualifiedName("javax.crypto.Cipher")
            .because("envelope encryption/decryption lives only in the rescue crypto boundary")
            .check(productionClasses)
    }

    /**
     * All randomness feeding tokens, codes, salts, or keys must come from
     * SecureRandom. java.util.Random (also backing kotlin.random and Math.random)
     * is predictable and banned outright in production code.
     */
    @Test
    fun noWeakRandomnessAnywhere() {
        noClasses()
            .should().dependOnClassesThat(
                object : DescribedPredicate<JavaClass>("are java.util.Random or kotlin.random") {
                    override fun test(input: JavaClass): Boolean =
                        input.name == "java.util.Random" ||
                            input.name.startsWith("kotlin.random.")
                },
            )
            .because("security-relevant values must be generated with SecureRandom")
            .check(productionClasses)
    }

    /**
     * SQL access stays inside the persistence layer: classes named *Store,
     * *Persistence, the SQLite write coordinator, or the transactional receipt
     * outbox (which shares the persistence connection for atomic enqueue).
     * Route handlers and services must go through those classes, never through
     * java.sql directly.
     */
    @Test
    fun sqlConfinedToPersistenceClasses() {
        classes()
            .that(
                object : DescribedPredicate<JavaClass>("directly depend on java.sql") {
                    override fun test(input: JavaClass): Boolean =
                        input.directDependenciesFromSelf.any {
                            it.targetClass.name.startsWith("java.sql.")
                        }
                },
            )
            .should().haveSimpleNameEndingWith("Store")
            .orShould().haveSimpleNameEndingWith("Persistence")
            .orShould().haveSimpleNameEndingWith("WriteCoordinator")
            .orShould().haveSimpleNameEndingWith("Outbox")
            .because("SQL must be reachable only through the persistence layer")
            .check(productionClasses)
    }

    /**
     * Module dependency direction: the Broker is a standalone relay and must not
     * reach into Gateway internals; shared domain code must not depend on either
     * server; the protocol module must not depend on any other Relay package.
     */
    @Test
    fun moduleDependencyDirectionIsEnforced() {
        noClasses()
            .that().resideInAPackage("com.example.relay.broker..")
            .should().dependOnClassesThat().resideInAPackage("com.example.relay.pcgateway..")
            .because("the Broker must stay deployable without the Gateway")
            .check(productionClasses)

        noClasses()
            .that().resideInAnyPackage(
                "com.example.relay.domain..",
                "com.example.relay.rescue..",
                "com.example.relay.qr..",
                "com.example.relay.data..",
            )
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.example.relay.pcgateway..",
                "com.example.relay.broker..",
            )
            .because("shared domain code must not depend on server modules")
            .check(productionClasses)

        noClasses()
            .that().resideInAPackage("com.example.relay.gateway.protocol..")
            .should().dependOnClassesThat(
                object : DescribedPredicate<JavaClass>("are Relay classes outside the protocol package") {
                    override fun test(input: JavaClass): Boolean =
                        input.name.startsWith("com.example.relay.") &&
                            !input.name.startsWith("com.example.relay.gateway.protocol.")
                },
            )
            .because("the protocol contract must not know about any implementation module")
            .check(productionClasses)
    }
}
