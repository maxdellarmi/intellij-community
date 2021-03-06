// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.vcs.commit

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.*
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode.UNVERSIONED_FILES_TAG
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager.Companion.LOCAL_CHANGES
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager.Companion.getToolWindowFor
import com.intellij.openapi.vcs.changes.ui.EditChangelistSupport
import com.intellij.openapi.vcs.changes.ui.VcsTreeModelData.*
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.IdeBorderFactory.createBorder
import com.intellij.ui.JBColor
import com.intellij.ui.SideBorder
import com.intellij.util.EventDispatcher
import com.intellij.util.ui.JBUI.Borders.empty
import com.intellij.util.ui.JBUI.Borders.emptyLeft
import com.intellij.util.ui.JBUI.Panels.simplePanel
import com.intellij.util.ui.tree.TreeUtil.*
import com.intellij.vcs.log.VcsUser
import javax.swing.JComponent
import javax.swing.SwingConstants
import kotlin.properties.Delegates.observable

internal fun ChangesBrowserNode<*>.subtreeRootObject(): Any? = (path.getOrNull(1) as? ChangesBrowserNode<*>)?.userObject

class ChangesViewCommitPanel(private val changesViewHost: ChangesViewPanel, private val rootComponent: JComponent) :
  NonModalCommitPanel(changesViewHost.changesView.project), ChangesViewCommitWorkflowUi {

  private val changesView get() = changesViewHost.changesView

  private val inclusionEventDispatcher = EventDispatcher.create(InclusionListener::class.java)

  private val toolbarPanel = simplePanel().apply {
    isOpaque = false
    border = emptyLeft(1)
  }
  private val commitAuthorComponent = CommitAuthorComponent(project)
  private val progressPanel = ChangesViewCommitProgressPanel(this, commitMessage.editorField)

  private var isHideToolWindowOnDeactivate = false

  var isToolbarHorizontal: Boolean by observable(false) { _, oldValue, newValue ->
    if (oldValue != newValue) {
      addToolbar(newValue) // this also removes toolbar from previous parent
    }
  }

  init {
    Disposer.register(this, commitMessage)

    bottomPanel = {
      add(progressPanel.apply { border = empty(6) })
      add(commitAuthorComponent.apply { border = empty(0, 5, 4, 0) })
      add(commitActionsPanel)
    }
    buildLayout()
    addToolbar(isToolbarHorizontal)

    for (support in EditChangelistSupport.EP_NAME.getExtensions(project)) {
      support.installSearch(commitMessage.editorField, commitMessage.editorField)
    }

    with(changesView) {
      setInclusionListener { inclusionEventDispatcher.multicaster.inclusionChanged() }
      isShowCheckboxes = true
    }
    changesViewHost.statusComponent =
      ChangesViewCommitStatusPanel(changesView, this).apply { addToLeft(toolbarPanel) }

    commitActionsPanel.setupShortcuts(rootComponent, this)
    commitActionsPanel.isCommitButtonDefault = {
      !progressPanel.isDumbMode &&
      IdeFocusManager.getInstance(project).getFocusedDescendantFor(rootComponent) != null
    }
  }

  private fun addToolbar(isHorizontal: Boolean) {
    if (isHorizontal) {
      toolbar.setOrientation(SwingConstants.HORIZONTAL)
      toolbar.setReservePlaceAutoPopupIcon(false)

      centerPanel.border = null
      toolbarPanel.addToCenter(toolbar.component)
    }
    else {
      toolbar.setOrientation(SwingConstants.VERTICAL)
      toolbar.setReservePlaceAutoPopupIcon(true)

      centerPanel.border = createBorder(JBColor.border(), SideBorder.LEFT)
      addToLeft(toolbar.component)
    }
  }

  override var commitAuthor: VcsUser?
    get() = commitAuthorComponent.commitAuthor
    set(value) {
      commitAuthorComponent.commitAuthor = value
    }

  override fun addCommitAuthorListener(listener: CommitAuthorListener, parent: Disposable) =
    commitAuthorComponent.addCommitAuthorListener(listener, parent)

  override var editedCommit by observable<EditedCommitDetails?>(null) { _, _, newValue ->
    refreshData()
    newValue?.let { expand(it) }
  }

  override val isActive: Boolean get() = isVisible

  override fun activate(): Boolean {
    val toolWindow = getVcsToolWindow() ?: return false
    val contentManager = ChangesViewContentManager.getInstance(project)

    saveToolWindowState()
    changesView.isShowCheckboxes = true
    isVisible = true
    commitActionsPanel.isActive = true

    contentManager.selectContent(LOCAL_CHANGES)
    toolWindow.activate({ commitMessage.requestFocusInMessage() }, false)
    return true
  }

  override fun deactivate(isRestoreState: Boolean) {
    if (isRestoreState) restoreToolWindowState()
    clearToolWindowState()
    changesView.isShowCheckboxes = false
    isVisible = false
    commitActionsPanel.isActive = false
  }

  private fun saveToolWindowState() {
    if (!isActive) {
      isHideToolWindowOnDeactivate = getVcsToolWindow()?.isVisible != true
    }
  }

  private fun restoreToolWindowState() {
    if (isHideToolWindowOnDeactivate) {
      getVcsToolWindow()?.hide(null)
    }
  }

  private fun clearToolWindowState() {
    isHideToolWindowOnDeactivate = false
  }

  private fun getVcsToolWindow(): ToolWindow? = getToolWindowFor(project, LOCAL_CHANGES)

  override fun expand(item: Any) {
    val node = changesView.findNodeInTree(item)
    node?.let { changesView.expandSafe(it) }
  }

  override fun select(item: Any) {
    val path = changesView.findNodePathInTree(item)
    path?.let { selectPath(changesView, it, false) }
  }

  override fun selectFirst(items: Collection<Any>) {
    if (items.isEmpty()) return

    val path = treePathTraverser(changesView).preOrderDfsTraversal().find { getLastUserObject(it) in items }
    path?.let { selectPath(changesView, it, false) }
  }

  override fun showCommitOptions(popup: JBPopup, isFromToolbar: Boolean, dataContext: DataContext) =
    when {
      isFromToolbar && isToolbarHorizontal -> popup.showAbove(toolbar.component)
      isFromToolbar && !isToolbarHorizontal -> popup.showAbove(this@ChangesViewCommitPanel)
      else -> popup.showInBestPositionFor(dataContext)
    }

  override fun setCompletionContext(changeLists: List<LocalChangeList>) {
    commitMessage.changeLists = changeLists
  }

  override fun refreshData() = ChangesViewManager.getInstanceEx(project).refreshImmediately()

  override fun getDisplayedChanges(): List<Change> = all(changesView).userObjects(Change::class.java)
  override fun getIncludedChanges(): List<Change> = included(changesView).userObjects(Change::class.java)

  override fun getDisplayedUnversionedFiles(): List<FilePath> =
    allUnderTag(changesView, UNVERSIONED_FILES_TAG).userObjects(FilePath::class.java)

  override fun getIncludedUnversionedFiles(): List<FilePath> =
    includedUnderTag(changesView, UNVERSIONED_FILES_TAG).userObjects(FilePath::class.java)

  override var inclusionModel: InclusionModel?
    get() = changesView.inclusionModel
    set(value) {
      changesView.setInclusionModel(value)
    }

  override fun includeIntoCommit(items: Collection<*>) = changesView.includeChanges(items)

  override fun addInclusionListener(listener: InclusionListener, parent: Disposable) =
    inclusionEventDispatcher.addListener(listener, parent)

  override val commitProgressUi: CommitProgressUi get() = progressPanel

  override fun endExecution() = closeEditorPreviewIfEmpty()

  private fun closeEditorPreviewIfEmpty() {
    val changesViewManager = ChangesViewManager.getInstance(project) as? ChangesViewManager ?: return
    if (!changesViewManager.isEditorPreview) return

    refreshData()
    changesViewManager.closeEditorPreview(true)
  }

  override fun dispose() {
    changesViewHost.statusComponent = null
    with(changesView) {
      isShowCheckboxes = false
      setInclusionListener(null)
    }
  }
}