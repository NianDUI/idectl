package com.niandui.idectl.core.test

import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.niandui.idectl.core.exec.ExecutionRegistry

/**
 * Captures the SM test-runner tree (07 §5) via the app/project TEST_STATUS topic and links it to
 * the owning session by ProcessHandler (SMRootTestProxy.getHandler). Structured, not text-sniffed —
 * covers JUnit direct runs and Gradle-delegated tests.
 */
class TestResultCollector(
    private val project: Project,
    private val registry: ExecutionRegistry,
) : SMTRunnerEventsListener {

    fun install() {
        project.messageBus.connect().subscribe(SMTRunnerEventsListener.TEST_STATUS, this)
    }

    override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) {
        try {
            val handler = testsRoot.handler ?: return
            registry.findByHandler(handler)?.testRoot = testsRoot
        } catch (t: Throwable) {
            thisLogger().warn("failed to link test root to session", t)
        }
    }

    // Remaining abstract events are unused — the tree is read lazily from the captured root.
    override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {}
    override fun onTestsCountInSuite(count: Int) {}
    override fun onTestStarted(test: SMTestProxy) {}
    override fun onTestFinished(test: SMTestProxy) {}
    override fun onTestFailed(test: SMTestProxy) {}
    override fun onTestIgnored(test: SMTestProxy) {}
    override fun onSuiteFinished(suite: SMTestProxy) {}
    override fun onSuiteStarted(suite: SMTestProxy) {}
    override fun onCustomProgressTestsCategory(categoryName: String?, testCount: Int) {}
    override fun onCustomProgressTestStarted() {}
    override fun onCustomProgressTestFailed() {}
    override fun onCustomProgressTestFinished() {}
    override fun onSuiteTreeNodeAdded(testProxy: SMTestProxy) {}
    override fun onSuiteTreeStarted(suite: SMTestProxy) {}
}
