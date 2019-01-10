package net.corda.node.flows

import net.corda.core.internal.*
import net.corda.core.internal.concurrent.transpose
import net.corda.core.messaging.startTrackedFlow
import net.corda.core.utilities.getOrThrow
import net.corda.node.internal.CheckpointIncompatibleException
import net.corda.node.internal.NodeStartup
import net.corda.testMessage.*
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.singleIdentity
import net.corda.testing.driver.DriverDSL
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.NodeParameters
import net.corda.testing.driver.driver
import net.corda.testing.node.TestCordapp
import net.corda.testing.node.internal.*
import net.test.cordapp.v1.Record
import net.test.cordapp.v1.SendMessageFlow
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class FlowCheckpointVersionNodeStartupCheckTest {
    companion object {
        val message = Message("Hello world!")
        val defaultCordapp = cordappWithPackages(
                MessageState::class.packageName, SendMessageFlow::class.packageName
        )
    }

    @Test
    fun `restart node successfully with suspended flow`() {
        return driver(parametersForRestartingNodes()) {
            createSuspendedFlowInBob(setOf(defaultCordapp))
            // Bob will resume the flow
            val alice = startNode(providedName = ALICE_NAME).getOrThrow()
            startNode(providedName = BOB_NAME).getOrThrow()
            val page = alice.rpc.vaultTrack(MessageState::class.java)
            val result = if (page.snapshot.states.isNotEmpty()) {
                page.snapshot.states.first()
            } else {
                val r = page.updates.timeout(5, TimeUnit.SECONDS).take(1).toBlocking().single()
                if (r.consumed.isNotEmpty()) r.consumed.first() else r.produced.first()
            }
            assertNotNull(result)
            assertEquals(message, result.state.data.message)
        }
    }

    @Test
    fun `restart node with incompatible version of suspended flow due to different jar name`() {
        driver(parametersForRestartingNodes()) {
            val uniqueName = "different-jar-name-test-${UUID.randomUUID()}"
            val cordapp = defaultCordapp.copy(name = uniqueName)

            val bobBaseDir = createSuspendedFlowInBob(setOf(cordapp))

            val cordappsDir = bobBaseDir / "cordapps"
            val cordappJar = cordappsDir.list().single { it.toString().endsWith(".jar") }
            // Make sure we're dealing with right jar
            assertThat(cordappJar.fileName.toString()).contains(uniqueName)
            // Rename the jar file.
            cordappJar.moveTo(cordappsDir / "renamed-${cordappJar.fileName}")

            assertBobFailsToStartWithLogMessage(
                    emptyList(),
                    CheckpointIncompatibleException.FlowNotInstalledException(SendMessageFlow::class.java).message
            )
        }
    }

    @Test
    fun `restart node with incompatible version of suspended flow due to different jar hash`() {
        driver(parametersForRestartingNodes()) {
            val uniqueWorkflowJarName = "different-jar-hash-test-${UUID.randomUUID()}"
            val uniqueContractJarName = "contract-$uniqueWorkflowJarName"
            val defaultWorkflowJar = cordappForClasses(
                    SendMessageFlow::class.java
            )
            val defaultContractJar = cordappForClasses(
                    MessageState::class.java,
                    MessageContract::class.java,
                    MessageSchema::class.java,
                    MessageSchemaV1::class.java,
                    Record::class.java
            )

            val contractJar = defaultContractJar.copy(name = uniqueContractJarName)
            val workflowJar = defaultWorkflowJar.copy(name = uniqueWorkflowJarName)

            val bobBaseDir = createSuspendedFlowInBob(setOf(workflowJar, contractJar))

            val cordappsDir = bobBaseDir / "cordapps"
            val cordappJar = cordappsDir.list().single {
               ! it.toString().contains(uniqueContractJarName) && it.toString().endsWith(".jar")
            }
            // Make sure we're dealing with right jar
            assertThat(cordappJar.fileName.toString()).contains(uniqueWorkflowJarName)

            // The name is part of the MANIFEST so changing it is sufficient to change the jar hash
            val modifiedCordapp = workflowJar.copy(name = "${workflowJar.name}-modified")
            val modifiedCordappJar = CustomCordapp.getJarFile(modifiedCordapp)
            modifiedCordappJar.moveTo(cordappJar, REPLACE_EXISTING)

            assertBobFailsToStartWithLogMessage(
                    emptyList(),
                    // The part of the log message generated by CheckpointIncompatibleException.FlowVersionIncompatibleException
                    "that is incompatible with the current installed version of"
            )
        }
    }

    private fun DriverDSL.createSuspendedFlowInBob(cordapps: Set<TestCordapp>): Path {
        val (alice, bob) = listOf(ALICE_NAME, BOB_NAME)
                .map { startNode(NodeParameters(providedName = it, additionalCordapps = cordapps)) }
                .transpose()
                .getOrThrow()
        alice.stop()
        val flowTracker = bob.rpc.startTrackedFlow(::SendMessageFlow, message, defaultNotaryIdentity, alice.nodeInfo.singleIdentity()).progress
        // Wait until Bob progresses as far as possible because Alice node is offline
        flowTracker.takeFirst { it == SendMessageFlow.Companion.FINALISING_TRANSACTION.label }.toBlocking().single()
        bob.stop()
        return bob.baseDirectory
    }

    private fun DriverDSL.assertBobFailsToStartWithLogMessage(cordapps: Collection<TestCordapp>, logMessage: String) {
        assertFailsWith(ListenProcessDeathException::class) {
            startNode(NodeParameters(
                    providedName = BOB_NAME,
                    customOverrides = mapOf("devMode" to false),
                    additionalCordapps = cordapps
            )).getOrThrow()
        }

        val logDir = baseDirectory(BOB_NAME) / NodeStartup.LOGS_DIRECTORY_NAME
        val logFile = logDir.list { it.filter { it.fileName.toString().endsWith(".log") }.findAny().get() }
        val matchingLineCount = logFile.readLines { it.filter { line -> logMessage in line }.count() }
        assertEquals(1, matchingLineCount)
    }

    private fun parametersForRestartingNodes(): DriverParameters {
        return DriverParameters(
                isDebug = true,
                startNodesInProcess = false, // Start nodes in separate processes to ensure CordappLoader is not shared between restarts
                inMemoryDB = false, // Ensure database is persisted between node restarts so we can keep suspended flows
                cordappsForAllNodes = emptyList()
        )
    }
}
