package com.cortlandwalker.endpointdroid.ui

import com.cortlandwalker.endpointdroid.model.Endpoint
import com.cortlandwalker.endpointdroid.services.EndpointConfigResolver
import com.cortlandwalker.endpointdroid.services.EndpointService
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.ui.ComboBox
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.HyperlinkEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel
import org.intellij.plugins.markdown.lang.MarkdownFileType

/**
 * Main UI for the EndpointDroid tool window.
 *
 * Layout:
 * - Top: tool window toolbar
 * - Left: searchable/filterable endpoint tree grouped by service
 * - Right: rendered endpoint documentation
 */
class EndpointDroidPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val properties = PropertiesComponent.getInstance(project)
    private val endpointMetadata = ConcurrentHashMap<EndpointKey, EndpointListMetadata>()
    private val scannedTreeContext = createTreeContext()
    private val postmanTreeContext = createTreeContext()
    private val insomniaTreeContext = createTreeContext()
    private val sourceTabs = JTabbedPane().apply {
        tabPlacement = JTabbedPane.TOP
        addTab(SourceTab.SCANNED.label, JBScrollPane(scannedTreeContext.tree))
        addTab(SourceTab.POSTMAN.label, JBScrollPane(postmanTreeContext.tree))
        addTab(SourceTab.INSOMNIA.label, JBScrollPane(insomniaTreeContext.tree))
    }

    private val searchField = SearchTextField().apply {
        toolTipText = "Search path, service, function, or response type"
    }
    private val authFilterCombo = ComboBox(AuthFilter.values())
    private val sortCombo = ComboBox(SortOption.values())
    private val methodFilterBoxes = linkedMapOf<String, JBCheckBox>()

    private val detailsVirtualFile = LightVirtualFile("EndpointDroidDetails.md", MarkdownFileType.INSTANCE, "")
    private val detailsPaneFallback = JEditorPane(detailsContentType(), "").apply {
        isEditable = false
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        foreground = UIUtil.getLabelForeground()
        background = UIUtil.getPanelBackground()
        font = UIUtil.getLabelFont()
    }

    private val splitPane = JSplitPane(
        JSplitPane.HORIZONTAL_SPLIT,
        buildLeftPanel(),
        JBScrollPane(detailsPaneFallback)
    ).apply {
        resizeWeight = DEFAULT_SPLIT_WEIGHT
    }

    private val endpointService = EndpointService.getInstance(project)
    private val refreshRequestId = AtomicLong(0)
    private val detailsRenderRequestId = AtomicLong(0)
    private val metadataRequestId = AtomicLong(0)

    private val recentSelections = linkedMapOf<EndpointKey, Long>()
    private var recentSequence = 0L
    private var scannedEndpoints: List<Endpoint> = emptyList()
    private var postmanEndpoints: List<Endpoint> = emptyList()
    private var insomniaEndpoints: List<Endpoint> = emptyList()

    init {
        // Tool window toolbar actions.
        val actions = DefaultActionGroup().apply {
            add(object : DumbAwareAction("Refresh", "Re-scan endpoints", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    scheduleRefresh(selectFirst = false)
                }
            })
            add(object : DumbAwareAction(
                "Config",
                "Open or create endpointdroid.yaml overrides",
                AllIcons.General.Settings
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    openOrCreateConfigFile()
                }
            })
            add(object : DumbAwareAction(
                "Import Collection",
                "Import a Postman or Insomnia collection (coming soon)",
                null
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    showDetailsMessage(IMPORT_COLLECTION_PLACEHOLDER_MESSAGE)
                }
            })
        }

        val toolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_TOOLBAR_BAR, actions, true)
        toolbar.targetComponent = this
        add(toolbar.component, BorderLayout.NORTH)

        add(splitPane, BorderLayout.CENTER)

        attachTreeSelectionListener(scannedTreeContext.tree)
        attachTreeSelectionListener(postmanTreeContext.tree)
        attachTreeSelectionListener(insomniaTreeContext.tree)
        val restoredSource = SourceTab.fromStorageValue(properties.getValue(SELECTED_SOURCE_TAB_KEY)) ?: SourceTab.SCANNED
        sourceTabs.selectedIndex = restoredSource.ordinal
        sourceTabs.addChangeListener {
            val source = selectedSourceTab()
            properties.setValue(SELECTED_SOURCE_TAB_KEY, source.storageValue)
            // Tab switching is intentionally UI-only: no scan/import job should start here.
            updateMethodFilterAvailability(endpointsFor(source))
            applyFiltersAndGrouping(selectFirst = false, preferredSelection = null)
        }

        detailsPaneFallback.addHyperlinkListener { event ->
            if (event.eventType != HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener
            val link = event.url?.toString() ?: event.description ?: return@addHyperlinkListener
            handleDetailsHyperlink(link)
        }

        registerFilterListeners()
        scheduleRefresh(selectFirst = true)
    }

    /**
     * Refreshes endpoints and reapplies filters/grouping in the left pane.
     */
    private fun refreshFromService(selectFirst: Boolean, requestId: Long) {
        val previousSelection = selectedEndpointKey()

        val refreshPromise = endpointService.refreshAsync()
        refreshPromise.onSuccess { endpoints ->
            ApplicationManager.getApplication().invokeLater {
                if (refreshRequestId.get() != requestId) return@invokeLater

                scannedEndpoints = endpoints
                endpointMetadata.clear()
                updateMethodFilterAvailability(endpointsFor(selectedSourceTab()))
                applyFiltersAndGrouping(selectFirst = selectFirst, preferredSelection = previousSelection)
                prefetchEndpointMetadata(endpoints, requestId)

                val refreshStatus = endpointService.getLastRefreshStatus()
                val hasSelectedEndpoint = selectedEndpoint() != null
                if (endpointsFor(selectedSourceTab()).isEmpty()) {
                    showDetailsMessage("${emptyStateMessageFor(selectedSourceTab())}\n\n$refreshStatus")
                } else if (!hasSelectedEndpoint) {
                    showDetailsMessage("$SELECT_ENDPOINT_MESSAGE\n\n$refreshStatus")
                }
            }
        }
        refreshPromise.onError { error ->
            ApplicationManager.getApplication().invokeLater {
                if (refreshRequestId.get() != requestId) return@invokeLater
                scannedEndpoints = emptyList()
                endpointMetadata.clear()
                treeContextFor(SourceTab.SCANNED).rootNode.removeAllChildren()
                treeContextFor(SourceTab.SCANNED).treeModel.reload()
                val refreshStatus = endpointService.getLastRefreshStatus()
                showDetailsMessage(
                    "$SCAN_FAILED_PREFIX ${error.message ?: error::class.java.simpleName}\n\n$refreshStatus"
                )
            }
        }
    }

    /**
     * Schedules refresh in smart mode to avoid index access while indexing.
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
     * Prefetches endpoint metadata off-EDT so auth/query badges and filters stay responsive.
     */
    private fun prefetchEndpointMetadata(endpoints: List<Endpoint>, requestId: Long) {
        if (endpoints.isEmpty()) return
        val metadataJobId = metadataRequestId.incrementAndGet()
        ApplicationManager.getApplication().executeOnPooledThread {
            val resolved = linkedMapOf<EndpointKey, EndpointListMetadata>()
            endpoints.forEach { endpoint ->
                if (project.isDisposed) return@executeOnPooledThread
                if (refreshRequestId.get() != requestId) return@executeOnPooledThread
                if (metadataRequestId.get() != metadataJobId) return@executeOnPooledThread

                val metadata = if (DumbService.isDumb(project)) {
                    EndpointListMetadata(
                        authRequirement = null,
                        queryCount = 0,
                        hasMultipart = false,
                        hasFormFields = false,
                        baseUrlResolved = !endpoint.baseUrl.isNullOrBlank(),
                        partial = true
                    )
                } else {
                    runCatching {
                        ApplicationManager.getApplication().runReadAction<EndpointDocDetails> {
                            EndpointDocDetailsResolver.resolve(project, endpoint)
                        }
                    }.map { details ->
                        EndpointListMetadata(
                            authRequirement = details.authRequirement,
                            queryCount = maxOf(details.queryParamDetails.size, details.queryParams.size),
                            hasMultipart = details.partParams.isNotEmpty() || details.hasPartMap,
                            hasFormFields = details.fieldParams.isNotEmpty() || details.hasFieldMap,
                            baseUrlResolved = !endpoint.baseUrl.isNullOrBlank(),
                            partial = false
                        )
                    }.getOrElse {
                        EndpointListMetadata(
                            authRequirement = null,
                            queryCount = 0,
                            hasMultipart = false,
                            hasFormFields = false,
                            baseUrlResolved = !endpoint.baseUrl.isNullOrBlank(),
                            partial = true
                        )
                    }
                }

                resolved[EndpointKey.from(endpoint)] = metadata
            }

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                if (refreshRequestId.get() != requestId) return@invokeLater
                if (metadataRequestId.get() != metadataJobId) return@invokeLater
                val preferredSelection = selectedEndpointKey()
                endpointMetadata.clear()
                endpointMetadata.putAll(resolved)
                applyFiltersAndGrouping(selectFirst = false, preferredSelection = preferredSelection)
            }
        }
    }

    /**
     * Registers listeners for search and filter controls.
     */
    private fun registerFilterListeners() {
        searchField.textEditor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFiltersAndGrouping(selectFirst = false)
            override fun removeUpdate(e: DocumentEvent) = applyFiltersAndGrouping(selectFirst = false)
            override fun changedUpdate(e: DocumentEvent) = applyFiltersAndGrouping(selectFirst = false)
        })
        authFilterCombo.addActionListener { applyFiltersAndGrouping(selectFirst = false) }
        sortCombo.addActionListener { applyFiltersAndGrouping(selectFirst = false) }
    }

    /**
     * Enables method filters relevant to current scan results.
     */
    private fun updateMethodFilterAvailability(endpoints: List<Endpoint>) {
        val presentMethods = endpoints.map { it.httpMethod.uppercase() }.toSet()
        val hadSelectedMethods = selectedMethods().isNotEmpty()
        methodFilterBoxes.forEach { (method, box) ->
            box.isEnabled = presentMethods.contains(method)
            // Keep checkbox state when a method is temporarily unavailable (e.g. switching tabs).
            // This avoids collapsing selections to a single method when coming back to scanned endpoints.
            if (box.isEnabled && !hadSelectedMethods) {
                box.isSelected = true
            }
        }
    }

    /**
     * Rebuilds the grouped tree from active filters and sort options.
     */
    private fun applyFiltersAndGrouping(selectFirst: Boolean, preferredSelection: EndpointKey? = selectedEndpointKey()) {
        val sourceTab = selectedSourceTab()
        val context = treeContextFor(sourceTab)
        val expandedServices = expandedServiceFqns()
        val sourceEndpoints = endpointsFor(sourceTab)
        val filteredEndpoints = filterEndpoints(sourceEndpoints)

        context.rootNode.removeAllChildren()
        val grouped = filteredEndpoints
            .groupBy { it.serviceFqn }
            .toSortedMap(compareBy({ shortServiceName(it) }, { it }))

        grouped.forEach { (serviceFqn, endpointsForService) ->
            val serviceNode = DefaultMutableTreeNode(EndpointServiceGroup(serviceFqn, endpointsForService.size))
            sortEndpoints(endpointsForService).forEach { endpoint ->
                serviceNode.add(DefaultMutableTreeNode(endpoint))
            }
            context.rootNode.add(serviceNode)
        }

        context.treeModel.reload()
        restoreExpansion(expandedServices)

        val selected = when {
            preferredSelection != null -> selectEndpointByKey(preferredSelection)
            selectFirst -> selectFirstEndpoint()
            else -> false
        }

        if (!selected && filteredEndpoints.isEmpty()) {
            if (sourceEndpoints.isEmpty()) {
                showDetailsMessage(emptyStateMessageFor(sourceTab))
            } else {
                showDetailsMessage(NO_MATCHING_ENDPOINTS_MESSAGE)
            }
        }
    }

    /**
     * Filters endpoints using search, method, and auth controls.
     */
    private fun filterEndpoints(endpoints: List<Endpoint>): List<Endpoint> {
        val query = searchField.text.trim().lowercase()
        val methods = selectedMethods().ifEmpty { METHOD_OPTIONS.toSet() }
        val authFilter = authFilterCombo.selectedItem as? AuthFilter ?: AuthFilter.ANY

        return endpoints.filter { endpoint ->
            val methodMatches = methods.contains(endpoint.httpMethod.uppercase())
            val searchMatches = if (query.isBlank()) {
                true
            } else {
                val haystack = listOf(
                    endpoint.path,
                    endpoint.serviceFqn,
                    endpoint.functionName,
                    endpoint.responseType.orEmpty()
                ).joinToString(" ").lowercase()
                haystack.contains(query)
            }
            val authMatches = when (authFilter) {
                AuthFilter.ANY -> true
                AuthFilter.REQUIRED -> {
                    endpointMetadata[EndpointKey.from(endpoint)]?.authRequirement == EndpointDocDetails.AuthRequirement.REQUIRED
                }
                AuthFilter.NONE -> {
                    endpointMetadata[EndpointKey.from(endpoint)]?.authRequirement == EndpointDocDetails.AuthRequirement.NONE
                }
            }
            methodMatches && searchMatches && authMatches
        }
    }

    /**
     * Sorts endpoints within each service group.
     */
    private fun sortEndpoints(endpoints: List<Endpoint>): List<Endpoint> {
        return when (sortCombo.selectedItem as? SortOption ?: SortOption.PATH) {
            SortOption.PATH -> endpoints.sortedWith(compareBy({ normalizeDisplayPath(it.path) }, { it.httpMethod.uppercase() }))
            SortOption.METHOD_THEN_PATH -> endpoints.sortedWith(compareBy({ it.httpMethod.uppercase() }, { normalizeDisplayPath(it.path) }))
            SortOption.SERVICE_THEN_PATH -> endpoints.sortedWith(compareBy({ normalizeDisplayPath(it.path) }, { it.httpMethod.uppercase() }))
            SortOption.RECENT -> endpoints.sortedWith(
                compareByDescending<Endpoint> { recentSelections[EndpointKey.from(it)] ?: Long.MIN_VALUE }
                    .thenBy { normalizeDisplayPath(it.path) }
            )
        }
    }

    /**
     * Builds the left pane containing quick filters and grouped endpoint list.
     */
    private fun buildLeftPanel(): JPanel {
        val methodRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(JBLabel("Methods:"))
            METHOD_OPTIONS.forEach { method ->
                val box = methodFilterBoxes.getOrPut(method) {
                    JBCheckBox(method, true).apply {
                        addActionListener { applyFiltersAndGrouping(selectFirst = false) }
                    }
                }
                add(box)
            }
        }

        val filterRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(authFilterCombo)
            add(sortCombo)
        }

        val controls = JPanel(BorderLayout()).apply {
            add(searchField, BorderLayout.NORTH)
            add(filterRow, BorderLayout.CENTER)
            add(methodRow, BorderLayout.SOUTH)
        }

        return JPanel(BorderLayout()).apply {
            add(controls, BorderLayout.NORTH)
            add(sourceTabs, BorderLayout.CENTER)
        }
    }

    /**
     * Renders markdown in the details pane.
     */
    private fun renderMarkdownDetails(markdown: String) {
        detailsVirtualFile.setContent(this, markdown, false)
        detailsPaneFallback.text = when (DETAILS_RENDER_MODE) {
            DetailsRenderMode.HTML_RENDERED -> MarkdownHtmlRenderer.toHtml(project, detailsVirtualFile, markdown)
            DetailsRenderMode.MARKDOWN_RAW -> MarkdownHtmlRenderer.toPlainHtml(markdown)
        }
        detailsPaneFallback.caretPosition = 0
    }

    /**
     * Returns the editor content type for the selected details render mode.
     */
    private fun detailsContentType(): String {
        return when (DETAILS_RENDER_MODE) {
            DetailsRenderMode.HTML_RENDERED -> "text/html"
            DetailsRenderMode.MARKDOWN_RAW -> "text/html"
        }
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

            is EndpointDocLinks.Target.Usage ->
                resolveUsageDescriptor(target.filePath, target.offset)
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
     * Opens a usage location captured from a method reference search result.
     */
    private fun resolveUsageDescriptor(filePath: String, offset: Int): OpenFileDescriptor? {
        val file = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return null
        return OpenFileDescriptor(project, file, offset.coerceAtLeast(0))
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
     * Shows non-endpoint information in the details pane.
     */
    private fun showDetailsMessage(message: String) {
        renderMarkdownDetails(message)
    }

    /**
     * Opens an existing config file, or creates a template config file and opens it.
     */
    private fun openOrCreateConfigFile() {
        val defaultPath = EndpointConfigResolver.defaultConfigPath(project)
        if (defaultPath == null) {
            showDetailsMessage("Cannot open config: project base path is unavailable.")
            return
        }

        showDetailsMessage("Opening EndpointDroid config...")

        ApplicationManager.getApplication().executeOnPooledThread {
            val configPath = EndpointConfigResolver.resolveConfigPath(project) ?: defaultPath

            val created = runCatching {
                if (Files.isRegularFile(configPath)) {
                    false
                } else {
                    // File creation is off-EDT because VFS and NIO I/O can block.
                    Files.createDirectories(configPath.parent)
                    Files.writeString(
                        configPath,
                        EndpointConfigResolver.templateContent(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW
                    )
                    true
                }
            }.getOrElse { error ->
                ApplicationManager.getApplication().invokeLater {
                    showDetailsMessage("Failed to create config: ${error.message ?: error::class.java.simpleName}")
                }
                return@executeOnPooledThread
            }

            val virtualFile = LocalFileSystem.getInstance()
                .refreshAndFindFileByNioFile(configPath)

            ApplicationManager.getApplication().invokeLater {
                if (virtualFile == null) {
                    showDetailsMessage("Config exists at: $configPath")
                    return@invokeLater
                }

                OpenFileDescriptor(project, virtualFile, 0).navigate(true)
                if (created) {
                    showDetailsMessage(
                        "Created endpointdroid.yaml template.\n\nEdit overrides, then click Refresh."
                    )
                }
            }
        }
    }

    /**
     * Creates a tree + model context shared by source tabs.
     */
    private fun createTreeContext(): EndpointTreeContext {
        val root = DefaultMutableTreeNode("root")
        val model = DefaultTreeModel(root)
        val tree = Tree(model).apply {
            isRootVisible = false
            showsRootHandles = true
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            cellRenderer = EndpointTreeCellRenderer { endpoint ->
                endpointMetadata[EndpointKey.from(endpoint)]
            }
        }
        return EndpointTreeContext(rootNode = root, treeModel = model, tree = tree)
    }

    /**
     * Resolves selected source tab from persisted project state.
     */
    private fun selectedSourceTab(): SourceTab {
        val selectedIndex = sourceTabs.selectedIndex
        if (selectedIndex in SourceTab.entries.indices) {
            return SourceTab.entries[selectedIndex]
        }
        val stored = properties.getValue(SELECTED_SOURCE_TAB_KEY)
        return SourceTab.fromStorageValue(stored) ?: SourceTab.SCANNED
    }

    /**
     * Maps source tabs to their backing tree context.
     */
    private fun treeContextFor(source: SourceTab): EndpointTreeContext {
        return when (source) {
            SourceTab.SCANNED -> scannedTreeContext
            SourceTab.POSTMAN -> postmanTreeContext
            SourceTab.INSOMNIA -> insomniaTreeContext
        }
    }

    /**
     * Returns the endpoint collection for a given source tab.
     */
    private fun endpointsFor(source: SourceTab): List<Endpoint> {
        return when (source) {
            SourceTab.SCANNED -> scannedEndpoints
            SourceTab.POSTMAN -> postmanEndpoints
            SourceTab.INSOMNIA -> insomniaEndpoints
        }
    }

    /**
     * Human-friendly empty state per source tab.
     */
    private fun emptyStateMessageFor(source: SourceTab): String {
        return when (source) {
            SourceTab.SCANNED -> NO_ENDPOINTS_MESSAGE
            SourceTab.POSTMAN -> NO_POSTMAN_ENDPOINTS_MESSAGE
            SourceTab.INSOMNIA -> NO_INSOMNIA_ENDPOINTS_MESSAGE
        }
    }

    /**
     * Adds shared endpoint selection behavior to each source tree.
     */
    private fun attachTreeSelectionListener(tree: Tree) {
        tree.addTreeSelectionListener {
            val endpoint = selectedEndpoint() ?: run {
                selectedServiceGroup()?.let { group ->
                    showDetailsMessage("${shortServiceName(group.serviceFqn)} (${group.count})\n\nSelect an endpoint to view details.")
                }
                return@addTreeSelectionListener
            }

            recordRecentSelection(endpoint)
            val requestId = detailsRenderRequestId.incrementAndGet()
            showDetailsMessage(LOADING_DETAILS_MESSAGE)

            ApplicationManager.getApplication().executeOnPooledThread {
                val markdown = runCatching {
                    val details = if (DumbService.isDumb(project)) {
                        EndpointDocDetails.empty()
                    } else {
                        ApplicationManager.getApplication().runReadAction<EndpointDocDetails> {
                            EndpointDocDetailsResolver.resolve(project, endpoint)
                        }
                    }
                    MarkdownDocRenderer.render(endpoint, details)
                }.getOrElse { error ->
                    "$DETAILS_FAILED_PREFIX ${error.message ?: error::class.java.simpleName}"
                }

                ApplicationManager.getApplication().invokeLater {
                    if (detailsRenderRequestId.get() != requestId) return@invokeLater
                    val selectedKey = selectedEndpointKey() ?: return@invokeLater
                    if (selectedKey != EndpointKey.from(endpoint)) return@invokeLater
                    renderMarkdownDetails(markdown)
                }
            }
        }
    }

    private fun selectedMethods(): Set<String> {
        return methodFilterBoxes
            .asSequence()
            .filter { it.value.isSelected }
            .map { it.key }
            .toSet()
    }

    private fun selectedEndpoint(): Endpoint? {
        val node = activeTreeContext().tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        return node.userObject as? Endpoint
    }

    private fun selectedServiceGroup(): EndpointServiceGroup? {
        val node = activeTreeContext().tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        return node.userObject as? EndpointServiceGroup
    }

    private fun selectedEndpointKey(): EndpointKey? {
        return selectedEndpoint()?.let { EndpointKey.from(it) }
    }

    private fun selectFirstEndpoint(): Boolean {
        val context = activeTreeContext()
        for (groupIndex in 0 until context.rootNode.childCount) {
            val groupNode = context.rootNode.getChildAt(groupIndex) as? DefaultMutableTreeNode ?: continue
            if (groupNode.childCount == 0) continue
            val endpointNode = groupNode.getChildAt(0) as? DefaultMutableTreeNode ?: continue
            context.tree.selectionPath = javax.swing.tree.TreePath(endpointNode.path)
            return true
        }
        return false
    }

    private fun selectEndpointByKey(key: EndpointKey): Boolean {
        val context = activeTreeContext()
        for (groupIndex in 0 until context.rootNode.childCount) {
            val groupNode = context.rootNode.getChildAt(groupIndex) as? DefaultMutableTreeNode ?: continue
            for (endpointIndex in 0 until groupNode.childCount) {
                val endpointNode = groupNode.getChildAt(endpointIndex) as? DefaultMutableTreeNode ?: continue
                val endpoint = endpointNode.userObject as? Endpoint ?: continue
                if (EndpointKey.from(endpoint) == key) {
                    context.tree.selectionPath = javax.swing.tree.TreePath(endpointNode.path)
                    return true
                }
            }
        }
        return false
    }

    private fun expandedServiceFqns(): Set<String> {
        val context = activeTreeContext()
        val expanded = linkedSetOf<String>()
        for (groupIndex in 0 until context.rootNode.childCount) {
            val groupNode = context.rootNode.getChildAt(groupIndex) as? DefaultMutableTreeNode ?: continue
            val group = groupNode.userObject as? EndpointServiceGroup ?: continue
            val path = javax.swing.tree.TreePath(groupNode.path)
            if (context.tree.isExpanded(path)) {
                expanded += group.serviceFqn
            }
        }
        return expanded
    }

    private fun restoreExpansion(expandedServiceFqns: Set<String>) {
        val context = activeTreeContext()
        for (groupIndex in 0 until context.rootNode.childCount) {
            val groupNode = context.rootNode.getChildAt(groupIndex) as? DefaultMutableTreeNode ?: continue
            val group = groupNode.userObject as? EndpointServiceGroup ?: continue
            val path = javax.swing.tree.TreePath(groupNode.path)
            if (expandedServiceFqns.isEmpty() || expandedServiceFqns.contains(group.serviceFqn)) {
                context.tree.expandPath(path)
            }
        }
    }

    /**
     * Returns the currently visible tree context based on source tab selection.
     */
    private fun activeTreeContext(): EndpointTreeContext {
        return treeContextFor(selectedSourceTab())
    }

    private fun recordRecentSelection(endpoint: Endpoint) {
        val key = EndpointKey.from(endpoint)
        recentSequence += 1
        recentSelections[key] = recentSequence
    }

    private fun shortServiceName(serviceFqn: String): String {
        return serviceFqn.substringAfterLast('.')
    }

    private fun normalizeDisplayPath(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path
        }
        return if (path.startsWith("/")) path else "/$path"
    }

    override fun dispose() {
        // No-op; panel owns no explicit disposable resources in fallback renderer mode.
    }

    /**
     * Endpoint source tabs shown under filter controls.
     */
    private enum class SourceTab(val label: String, val storageValue: String) {
        SCANNED("EndpointDroid", "scanned"),
        POSTMAN("Postman", "postman"),
        INSOMNIA("Insomnia", "insomnia");

        companion object {
            fun fromStorageValue(value: String?): SourceTab? {
                return entries.firstOrNull { it.storageValue.equals(value, ignoreCase = true) }
            }
        }
    }

    /**
     * Holds tree model state per source tab.
     */
    private data class EndpointTreeContext(
        val rootNode: DefaultMutableTreeNode,
        val treeModel: DefaultTreeModel,
        val tree: Tree
    )

    private enum class AuthFilter(private val label: String) {
        ANY("Auth: Any"),
        REQUIRED("Auth: Required"),
        NONE("Auth: None");

        override fun toString(): String = label
    }

    private enum class SortOption(private val label: String) {
        PATH("Sort: Path (A-Z)"),
        METHOD_THEN_PATH("Sort: Method then Path"),
        SERVICE_THEN_PATH("Sort: Service then Path"),
        RECENT("Sort: Recently selected");

        override fun toString(): String = label
    }

    /**
     * Rendering strategy for the right-side details panel.
     */
    private enum class DetailsRenderMode {
        HTML_RENDERED,
        MARKDOWN_RAW
    }

    private companion object {
        // One-line test switch:
        // - HTML_RENDERED: markdown->HTML with EndpointDroid styling transforms
        // - MARKDOWN_RAW: markdown->HTML parser output only (no custom styling)
        val DETAILS_RENDER_MODE = DetailsRenderMode.MARKDOWN_RAW
        const val DEFAULT_SPLIT_WEIGHT = 0.45
        const val INDEXING_MESSAGE = "Indexing..."
        const val REFRESHING_MESSAGE = "Refreshing endpoints..."
        const val LOADING_DETAILS_MESSAGE = "Loading endpoint details..."
        const val NO_ENDPOINTS_MESSAGE = "No endpoints found."
        const val NO_POSTMAN_ENDPOINTS_MESSAGE =
            "Postman tab is empty.\n\nImport a Postman collection to populate endpoints."
        const val NO_INSOMNIA_ENDPOINTS_MESSAGE =
            "Insomnia tab is empty.\n\nImport an Insomnia export to populate endpoints."
        const val NO_MATCHING_ENDPOINTS_MESSAGE = "No endpoints match the current filters."
        const val SELECT_ENDPOINT_MESSAGE = "Select an endpoint to view details."
        const val SELECTED_SOURCE_TAB_KEY = "endpointdroid.selected.source.tab"
        const val IMPORT_COLLECTION_PLACEHOLDER_MESSAGE =
            "Import collection support is coming soon.\n\nPlanned: Postman + Insomnia import."
        const val SCAN_FAILED_PREFIX = "Endpoint scan failed:"
        const val DETAILS_FAILED_PREFIX = "Endpoint details failed:"
        val METHOD_OPTIONS = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "HTTP")
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
