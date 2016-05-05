package generateTestsPlugin

import com.intellij.execution.PsiLocation
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.application.ApplicationConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.junit.JUnitConfiguration
import com.intellij.execution.junit.JUnitConfigurationType
import com.intellij.execution.junit.JUnitUtil
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.notification.Notification
import com.intellij.notification.NotificationListener
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileAdapter
import com.intellij.openapi.vfs.VirtualFileEvent
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import java.util.*
import javax.swing.SwingUtilities
import javax.swing.event.HyperlinkEvent

class GenerateTestsActions : DumbAwareAction() {
    private val RUN_CONFIGURATION_NAME = "Generate Tests"

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project!!

        val runConfiguration = RunManager.getInstance(project)
                .getConfigurationSettingsList(ApplicationConfigurationType.getInstance())
                .singleOrNull { it.name == RUN_CONFIGURATION_NAME }
        if (runConfiguration == null) {
            Messages.showMessageDialog(project, "Could not find run configuration named \"$RUN_CONFIGURATION_NAME\"", "Generate Tests", null)
            return
        }

        Executor(project).execute(runConfiguration)
    }

    private class Executor(private val project: Project) {
        private val fileIndex = ProjectRootManager.getInstance(project).fileIndex

        private val changedFileToOldData = HashMap<VirtualFile, ChangedFileData>()

        private data class ChangedFileData(val classFqName: String, val methodNames: Collection<String>)

        private val fileListener = object : VirtualFileAdapter() {
            override fun beforeContentsChange(event: VirtualFileEvent) {
                if (!event.isFromRefresh) return
                val file = event.file
                if (changedFileToOldData.containsKey(file)) return

                if (!fileIndex.isInSource(file) || fileIndex.isInLibrarySource(file)) return

                //TODO: check under test sources

                val fileData = extractFileData(file)
                if (fileData != null) {
                    changedFileToOldData.put(file, ChangedFileData(fileData.classFqName, fileData.methods.keys))
                }
            }
        }

        fun execute(runConfiguration: RunnerAndConfigurationSettings) {
            val runExecutor = DefaultRunExecutor.getRunExecutorInstance()
            val environment = ExecutionEnvironmentBuilder.create(runExecutor, runConfiguration)
                    .contentToReuse(null)
                    .dataContext(null)
                    .activeTarget()
                    .build()
            environment.assignNewExecutionId()

            val virtualFileManager = VirtualFileManager.getInstance()
            virtualFileManager.addVirtualFileListener(fileListener)

            fun onProcessExited() {
                virtualFileManager.asyncRefresh {
                    virtualFileManager.removeVirtualFileListener(fileListener)

                    DumbService.getInstance(project).smartInvokeLater {
                        val newTestMethods = detectNewTests()
                        if (newTestMethods.isNotEmpty()) {
                            showNotificationForNewTests(newTestMethods)
                        }
                    }
                }
            }

            fun onProcessNotExited() {
                virtualFileManager.removeVirtualFileListener(fileListener)
            }

            environment.runner.execute(environment, ProgramRunner.Callback { descriptor ->
                val processHandler = descriptor.processHandler
                if (processHandler == null) {
                    onProcessNotExited()
                    return@Callback
                }

                if (processHandler.isProcessTerminated) {
                    onProcessExited()
                    return@Callback
                }

                processHandler.addProcessListener(object: ProcessAdapter() {
                    override fun processTerminated(event: ProcessEvent) {
                        onProcessExited()
                    }
                })
            })
        }

        private fun detectNewTests(): Collection<PsiMethod> {
            val newMethods = ArrayList<PsiMethod>()
            for ((file, oldFileData) in changedFileToOldData) {
                val newFileData = extractFileData(file) ?: continue

                val classFqName = newFileData.classFqName
                if (oldFileData.classFqName != classFqName) continue
                val commonNames = oldFileData.methodNames.intersect(newFileData.methods.keys)
                val addedNames = newFileData.methods.keys - commonNames

                addedNames
                        .map { newFileData.methods[it]!! }
                        .filterTo(newMethods) { JUnitUtil.isTestMethod(PsiLocation(it)) }
            }
            return newMethods
        }

        private data class FileData(val classFqName: String, val methods: Map<String, PsiMethod>)

        private fun extractFileData(file: VirtualFile): FileData? {
            val psiFile = PsiManager.getInstance(project).findFile(file) as? PsiClassOwner ?: return null
            val psiClass = psiFile.classes.singleOrNull() ?: return null
            val qualifiedName = psiClass.qualifiedName ?: return null

            val methods = HashMap<String, PsiMethod>().collectMethods(psiClass, "")

            return FileData(qualifiedName, methods)
        }

        private fun MutableMap<String, PsiMethod>.collectMethods(psiClass: PsiClass, prefix: String): Map<String, PsiMethod> {
            for (method in psiClass.methods) {
                if (method.isConstructor) continue
                if (method.parameterList.parametersCount != 0) continue
                if (!method.hasModifierProperty(PsiModifier.PUBLIC)) continue
                if (method.hasModifierProperty(PsiModifier.STATIC)) continue
                put(prefix + method.name, method)
            }

            for (innerClass in psiClass.innerClasses) {
                if (innerClass.hasModifierProperty(PsiModifier.PUBLIC) && innerClass.hasModifierProperty(PsiModifier.STATIC)) {
                    val name = innerClass.name
                    val newPrefix = if (prefix.isEmpty()) "$name." else "$prefix$name."
                    collectMethods(innerClass, newPrefix)
                }
            }

            return this
        }

        private fun showNotificationForNewTests(newTestMethods: Collection<PsiMethod>) {
            assert(newTestMethods.isNotEmpty())
            val methods = newTestMethods.sortedBy { buildPresentation(it) }

            val containers = ArrayList<PsiClass>()
            run {
                var container = findCommonContainer(methods)
                while (container != null) {
                    containers.add(container)
                    container = container.containingClass
                }
            }

            val linkActions = ArrayList<(Notification) -> Unit>()

            fun addLink(action: () -> Unit, hideBalloon: Boolean): String {
                linkActions.add { notification ->
                    action()
                    if (hideBalloon) {
                        notification.hideBalloon()
                    }
                }
                return linkActions.lastIndex.toString()
            }

            val text = buildString {
                val size = methods.size
                when (size) {
                    1 -> {
                        val configuration = buildTestConfiguration(methods.single())
                        val action = buildExecuteOneConfigurationAction(configuration)
                        val linkId = addLink(action, hideBalloon = true)

                        append("<a href=\"$linkId\">")
                        append(buildPresentation(methods.single()))
                        append("</a> added.<br>Click to run it.")
                    }

                    //TODO: handle when too many new tests
                    else -> {
                        append("New tests:<br>")
                        for (method in methods) {
                            val configuration = buildTestConfiguration(method)
                            val action = buildExecuteOneConfigurationAction(configuration)
                            val linkId = addLink(action, hideBalloon = false)

                            append("&nbsp;&nbsp;")
                            append("<a href=\"$linkId\">")
                            append(buildPresentation(method))
                            append("</a><br>")
                        }

                        val configurations = buildTestConfigurations(methods)
                        val action = buildExecuteConfigurationsAction(configurations)
                        val linkId = addLink(action, hideBalloon = true)
                        append("<br>Click on a test to run it or <a href=\"$linkId\">run them all</a>.")
                    }
                }

                if (containers.isNotEmpty()) {
                    append("<br><br>Or run the whole class:<br>")
                    for (container in containers) {
                        val configuration = buildTestConfiguration(container)
                        val action = buildExecuteOneConfigurationAction(configuration)
                        val linkId = addLink(action, hideBalloon = true)

                        append("&nbsp;&nbsp;")
                        append("<a href=\"$linkId\">")
                        append(buildPresentation(container))
                        append("</a><br>")
                    }
                }
            }

            val notification = Notification(Notifications.SYSTEM_MESSAGES_GROUP_ID,
                    "New Tests Generated",
                    text,
                    NotificationType.INFORMATION,
                    NotificationListener { notification, event ->
                        if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                            val linkId = event.description.toInt()
                            linkActions[linkId](notification)
                        }
                    })
            Notifications.Bus.notify(notification, project)
        }

        private fun buildPresentation(method: PsiMethod): String {
            return buildPresentation(method.containingClass!!) + "." + method.name
        }

        private fun buildPresentation(klass: PsiClass): String {
            var container = klass.containingClass
            var result = klass.name!!
            while (container != null) {
                result = container.name + "." + result
                container = container.containingClass
            }
            return result
        }

        private fun findCommonContainer(psiMethods: List<PsiMethod>): PsiClass? {
            var parent = PsiTreeUtil.findCommonParent(psiMethods) ?: return null
            while (parent !is PsiFile) {
                if (parent is PsiClass) return parent
                parent = parent.parent ?: return null
            }
            return null
        }

        private fun buildExecuteOneConfigurationAction(configuration: RunConfiguration): () -> Unit {
            return { executeConfiguration(configuration) }
        }

        private fun buildExecuteConfigurationsAction(configurations: List<RunConfiguration>): () -> Unit {
            return {
                val iterator = configurations.iterator()

                var executionId: Long? = null
                fun executeNext() {
                    if (iterator.hasNext()) {
                        val configuration = iterator.next()
                        executionId = executeConfigurationWithPostAction(configuration, executionId, ::executeNext)
                    }
                }

                executeNext()
            }
        }

        private fun buildTestConfigurations(psiMethods: Collection<PsiMethod>): List<RunConfiguration> {
            if (psiMethods.size == 1) {
                return listOf(buildTestConfiguration(psiMethods.single()))
            }

            val methodsByName = psiMethods.groupBy { it.name }
            val configurations = ArrayList<RunConfiguration>(methodsByName.size)
            for (sameNamePsiMethods in methodsByName.values) {
                val configuration = createTemplateConfiguration().apply {
                    bePatternConfiguration(sameNamePsiMethods.map { it.containingClass }, sameNamePsiMethods.first())
                }
                configurations.add(configuration)
            }
            return configurations
        }

        private fun buildTestConfiguration(psiMethod: PsiMethod): JUnitConfiguration {
            return createTemplateConfiguration().apply {
                beMethodConfiguration(PsiLocation.fromPsiElement(psiMethod))
            }
        }

        private fun buildTestConfiguration(psiClass: PsiClass): JUnitConfiguration {
            return createTemplateConfiguration().apply {
                beClassConfiguration(psiClass)
            }
        }

        private fun createTemplateConfiguration(): JUnitConfiguration {
            val configurationFactory = JUnitConfigurationType.getInstance().configurationFactories.single()
            val runnerAndConfigurationSettings = RunManager.getInstance(project).createRunConfiguration("", configurationFactory)
            return runnerAndConfigurationSettings.configuration as JUnitConfiguration
        }

        private fun executeConfiguration(configuration: RunConfiguration, executionId: Long? = null, callback: ProgramRunner.Callback? = null): Long {
            val runExecutor = DefaultRunExecutor.getRunExecutorInstance()
            val environment = ExecutionEnvironmentBuilder.create(runExecutor, configuration)
                    .contentToReuse(null)
                    .dataContext(null)
                    .activeTarget()
                    .build()
            if (executionId != null) {
                environment.executionId = executionId
            }
            else {
                environment.assignNewExecutionId()
            }
            environment.runner.execute(environment, callback)
            return environment.executionId
        }

        private fun executeConfigurationWithPostAction(configuration: RunConfiguration, executionId: Long?, postAction: () -> Unit): Long {
            return executeConfiguration(configuration, executionId, ProgramRunner.Callback { descriptor ->
                val processHandler = descriptor.processHandler ?: return@Callback

                if (processHandler.isProcessTerminated) {
                    postAction()
                    return@Callback
                }

                processHandler.addProcessListener(object: ProcessAdapter() {
                    override fun processTerminated(event: ProcessEvent) {
                        SwingUtilities.invokeLater {
                            postAction()
                        }
                    }
                })
            })
        }
    }
}