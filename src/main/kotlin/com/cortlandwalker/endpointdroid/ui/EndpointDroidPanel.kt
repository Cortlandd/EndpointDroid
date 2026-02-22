package com.cortlandwalker.endpointdroid.ui

import com.cortlandwalker.endpointdroid.model.Endpoint
import com.cortlandwalker.endpointdroid.services.EndpointService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel
import javax.swing.event.HyperlinkEvent
import java.util.concurrent.atomic.AtomicLong

/**
 * Main UI for the EndpointDroid tool window.
 *
 * Layout:
 * - Top: tool window toolbar (Refresh now; Export later)
 * - Left: endpoint list
 * - Right: rendered endpoint documentation
 *
 * The panel does NOT own the endpoint data; it delegates to [EndpointService].
 */
class EndpointDroidPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val endpointList = JBList<Endpoint>().apply {
        cellRenderer = EndpointListCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        emptyText.text = EMPTY_LIST_MESSAGE
    }
    private val detailsPane = JEditorPane("text/html", "").apply {
        isEditable = false
    }
    private val splitPane = JSplitPane(
        JSplitPane.HORIZONTAL_SPLIT,
        JBScrollPane(endpointList),
        JBScrollPane(detailsPane)
    ).apply {
        resizeWeight = DEFAULT_SPLIT_WEIGHT
    }

    private val endpointService = EndpointService.getInstance(project)
    private val refreshRequestId = AtomicLong(0)
    private val detailsRenderRequestId = AtomicLong(0)

    init {
        // Tool window toolbar actions.
        val actions = DefaultActionGroup().apply {
            add(object : DumbAwareAction("Refresh", "Re-scan endpoints", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    scheduleRefresh(selectFirst = false)
                }
            })
        }

        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_TOOLBAR_BAR, actions, true)
        toolbar.targetComponent = this
        add(toolbar.component, BorderLayout.NORTH)

        // Two-pane split: endpoints list (left) and docs (right).
        add(splitPane, BorderLayout.CENTER)

        // When user selects an endpoint, render docs in the right pane.
        endpointList.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            val ep = endpointList.selectedValue ?: return@addListSelectionListener
            val requestId = detailsRenderRequestId.incrementAndGet()
            showDetailsMessage(LOADING_DETAILS_MESSAGE)

            ApplicationManager.getApplication().executeOnPooledThread {
                val markdown = runCatching {
                    val details = if (DumbService.isDumb(project)) {
                        EndpointDocDetails.empty()
                    } else {
                        ApplicationManager.getApplication().runReadAction<EndpointDocDetails> {
                            EndpointDocDetailsResolver.resolve(project, ep)
                        }
                    }
                    MarkdownDocRenderer.render(ep, details)
                }.getOrElse { error ->
                    "$DETAILS_FAILED_PREFIX ${error.message ?: error::class.java.simpleName}"
                }

                ApplicationManager.getApplication().invokeLater {
                    if (detailsRenderRequestId.get() != requestId) return@invokeLater
                    if (endpointList.selectedValue != ep) return@invokeLater
                    renderMarkdownDetails(markdown)
                }
            }
        }
        detailsPane.addHyperlinkListener { event ->
            if (event.eventType != HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener
            val link = event.url?.toString() ?: event.description ?: return@addHyperlinkListener
            handleDetailsHyperlink(link)
        }

        scheduleRefresh(selectFirst = true)
    }

    /**
     * Refreshes the endpoint list from the project service and updates UI state.
     *
     * @param selectFirst if true, selects the first endpoint after refresh (useful on initial load).
     */
    private fun refreshFromService(selectFirst: Boolean, requestId: Long) {
        val previousSelection = endpointList.selectedValue

        val refreshPromise = endpointService.refreshAsync()
        refreshPromise.onSuccess { endpoints ->
            ApplicationManager.getApplication().invokeLater {
                if (refreshRequestId.get() != requestId) return@invokeLater
                val refreshStatus = endpointService.getLastRefreshStatus()
                endpointList.setListData(endpoints.toTypedArray())

                if (endpoints.isEmpty()) {
                    showDetailsMessage("$NO_ENDPOINTS_MESSAGE\n\n$refreshStatus")
                    return@invokeLater
                }

                val preservedIndex = previousSelection?.let { previous ->
                    endpoints.indexOfFirst {
                        it.httpMethod == previous.httpMethod &&
                            it.path == previous.path &&
                            it.serviceFqn == previous.serviceFqn &&
                            it.functionName == previous.functionName
                    }
                } ?: -1

                when {
                    preservedIndex >= 0 -> endpointList.selectedIndex = preservedIndex
                    selectFirst -> endpointList.selectedIndex = 0
                    else -> {
                        endpointList.clearSelection()
                        showDetailsMessage("$SELECT_ENDPOINT_MESSAGE\n\n$refreshStatus")
                    }
                }
            }
        }
        refreshPromise.onError { error ->
            ApplicationManager.getApplication().invokeLater {
                if (refreshRequestId.get() != requestId) return@invokeLater
                endpointList.setListData(emptyArray())
                val refreshStatus = endpointService.getLastRefreshStatus()
                showDetailsMessage(
                    "$SCAN_FAILED_PREFIX ${error.message ?: error::class.java.simpleName}\n\n$refreshStatus"
                )
            }
        }
    }

    /**
     * Schedules a refresh using built-in smart-mode coordination.
     *
     * - If indexing is active, show an indexing state and refresh when smart mode resumes.
     * - If already smart, queue refresh immediately while still showing in-window status.
     */
    private fun scheduleRefresh(selectFirst: Boolean) {
        val requestId = refreshRequestId.incrementAndGet()
        val dumbService = DumbService.getInstance(project)
        if (dumbService.isDumb) {
            showDetailsMessage(INDEXING_MESSAGE)
        } else {
            showDetailsMessage(REFRESHING_MESSAGE)
        }

        dumbService.smartInvokeLater {
            if (project.isDisposed) return@smartInvokeLater
            if (refreshRequestId.get() != requestId) return@smartInvokeLater
            showDetailsMessage(REFRESHING_MESSAGE)
            refreshFromService(selectFirst, requestId)
        }
    }

    /**
     * Renders markdown in the details pane.
     */
    private fun renderMarkdownDetails(markdown: String) {
        detailsPane.text = MarkdownHtmlRenderer.toHtml(markdown)
        detailsPane.caretPosition = 0
    }

    /**
     * Resolves custom markdown links and navigates to matching source declarations.
     */
    private fun handleDetailsHyperlink(link: String) {
        val target = EndpointDocLinks.parse(link) ?: return
        DumbService.getInstance(project).runWhenSmart {
            ApplicationManager.getApplication().executeOnPooledThread {
                val descriptor = ApplicationManager.getApplication().runReadAction<OpenFileDescriptor?> {
                    resolveNavigationDescriptor(target)
                } ?: return@executeOnPooledThread

                ApplicationManager.getApplication().invokeLater {
                    descriptor.navigate(true)
                }
            }
        }
    }

    /**
     * Maps a parsed navigation target to an editor descriptor.
     */
    private fun resolveNavigationDescriptor(target: EndpointDocLinks.Target): OpenFileDescriptor? {
        return when (target) {
            is EndpointDocLinks.Target.Function ->
                resolveFunctionDescriptor(target.serviceFqn, target.functionName)

            is EndpointDocLinks.Target.Service ->
                resolveServiceDescriptor(target.serviceFqn)

            is EndpointDocLinks.Target.Type ->
                resolveTypeDescriptor(target.typeText)
        }
    }

    /**
     * Opens the source declaration for a service function.
     */
    private fun resolveFunctionDescriptor(serviceFqn: String, functionName: String): OpenFileDescriptor? {
        val javaFacade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.allScope(project)
        val serviceClass = javaFacade.findClass(serviceFqn, scope) ?: return null

        val method = serviceClass.methods.firstOrNull { candidate ->
            candidate.name == functionName && hasRetrofitHttpAnnotation(candidate)
        } ?: serviceClass.methods.firstOrNull { candidate ->
            candidate.name == functionName
        } ?: return null

        return descriptorForElement(method)
    }

    /**
     * Opens the source declaration for a service/interface type.
     */
    private fun resolveServiceDescriptor(serviceFqn: String): OpenFileDescriptor? {
        val javaFacade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.allScope(project)
        val serviceClass = javaFacade.findClass(serviceFqn, scope) ?: return null
        return descriptorForElement(serviceClass)
    }

    /**
     * Opens a best-effort type declaration from a rendered type string.
     */
    private fun resolveTypeDescriptor(typeText: String): OpenFileDescriptor? {
        val resolvedClass = resolveTypeClass(typeText) ?: return null
        return descriptorForElement(resolvedClass)
    }

    /**
     * Resolves a source class from a potentially generic type string.
     */
    private fun resolveTypeClass(typeText: String): PsiClass? {
        val javaFacade = JavaPsiFacade.getInstance(project)
        val allScope = GlobalSearchScope.allScope(project)
        val projectScope = GlobalSearchScope.projectScope(project)
        val shortNamesCache = PsiShortNamesCache.getInstance(project)

        val candidates = Regex("[A-Za-z_][A-Za-z0-9_$.]*")
            .findAll(typeText)
            .map { it.value }
            .filterNot { ignoredTypeNames.contains(it) }
            .toList()

        for (candidate in candidates) {
            if (candidate.contains('.')) {
                javaFacade.findClass(candidate, allScope)?.let { return it }
            }

            val shortName = candidate.substringAfterLast('.')
            val classes = shortNamesCache.getClassesByName(shortName, projectScope)
            if (classes.isNotEmpty()) {
                return classes.minByOrNull { it.qualifiedName ?: it.name ?: "" }
            }
        }

        return null
    }

    /**
     * Creates a source descriptor for the given PSI element if it has a file-backed location.
     */
    private fun descriptorForElement(element: PsiElement): OpenFileDescriptor? {
        val navElement = element.navigationElement.takeIf { it.isValid } ?: element
        val file = navElement.containingFile?.virtualFile ?: return null
        val offset = navElement.textOffset.coerceAtLeast(0)
        return OpenFileDescriptor(project, file, offset)
    }

    /**
     * Checks whether a method carries one of Retrofit's HTTP annotations.
     */
    private fun hasRetrofitHttpAnnotation(method: PsiMethod): Boolean {
        return method.modifierList.annotations.any { ann ->
            val qName = ann.qualifiedName ?: return@any false
            qName.startsWith("retrofit2.http.")
        }
    }

    /**
     * Shows non-endpoint information in the details pane and resets scroll position.
     */
    private fun showDetailsMessage(message: String) {
        renderMarkdownDetails(message)
    }

    private companion object {
        const val DEFAULT_SPLIT_WEIGHT = 0.45
        const val EMPTY_LIST_MESSAGE = "Endpoints will appear here after refresh."
        const val INDEXING_MESSAGE = "Indexing..."
        const val REFRESHING_MESSAGE = "Refreshing endpoints..."
        const val LOADING_DETAILS_MESSAGE = "Loading endpoint details..."
        const val NO_ENDPOINTS_MESSAGE = "No endpoints found."
        const val SELECT_ENDPOINT_MESSAGE = "Select an endpoint to view details."
        const val SCAN_FAILED_PREFIX = "Endpoint scan failed:"
        const val DETAILS_FAILED_PREFIX = "Endpoint details failed:"
        val ignoredTypeNames = setOf(
            "String",
            "Int",
            "Long",
            "Float",
            "Double",
            "Boolean",
            "Any",
            "Object",
            "Unit",
            "Void",
            "List",
            "Set",
            "Map",
            "MutableList",
            "MutableSet",
            "MutableMap",
            "Call",
            "Response"
        )
    }
}
