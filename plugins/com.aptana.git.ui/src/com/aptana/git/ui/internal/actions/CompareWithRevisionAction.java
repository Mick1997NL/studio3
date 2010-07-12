package com.aptana.git.ui.internal.actions;

import org.eclipse.compare.CompareUI;
import org.eclipse.compare.ITypedElement;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.team.core.history.IFileRevision;
import org.eclipse.team.internal.ui.history.FileRevisionTypedElement;
import org.eclipse.team.ui.synchronize.SaveableCompareEditorInput;

import com.aptana.git.core.GitPlugin;
import com.aptana.git.core.model.GitCommit;
import com.aptana.git.core.model.GitRepository;
import com.aptana.git.ui.actions.GitAction;
import com.aptana.git.ui.internal.history.GitCompareFileRevisionEditorInput;

@SuppressWarnings("restriction")
public class CompareWithRevisionAction extends GitAction
{

	@Override
	public void run()
	{
		IResource[] resources = getSelectedResources();
		if (resources == null || resources.length != 1)
			return;
		IResource resource = resources[0];
		if (resource.getType() != IResource.FILE)
			return;
		GitRepository repo = getGitRepositoryManager().getAttached(resource.getProject());
		if (repo == null)
			return;

		// Need the repo relative path
		IPath resourcePath = repo.relativePath(resource);

		// TODO Grab the list of all revisions for this file and show them in a drop down UI list?
		// GitRevList revList = new GitRevList(repo);
		// repo.lazyReload();
		// revList.walkRevisionListWithSpecifier(new GitRevSpecifier(resourcePath.toOSString()),
		// new NullProgressMonitor());
		// final List<GitCommit> commits = revList.getCommits();

		InputDialog dialog = new InputDialog(getShell(), "Select Revsion", "Enter revision SHA", "", null);
		if (dialog.open() == Window.CANCEL)
		{
			return;
		}
		String sha = dialog.getValue();

		ITypedElement base = SaveableCompareEditorInput.createFileElement((IFile) resource);
		final IFileRevision nextFile = GitPlugin.revisionForCommit(new GitCommit(repo, sha),
				resourcePath.toPortableString());
		final ITypedElement next = new FileRevisionTypedElement(nextFile);
		final GitCompareFileRevisionEditorInput in = new GitCompareFileRevisionEditorInput(base, next, null);
		CompareUI.openCompareEditor(in);
	}

	@Override
	public boolean isEnabled()
	{
		IResource[] resources = getSelectedResources();
		if (resources == null || resources.length != 1)
			return false;
		IResource resource = resources[0];
		if (resource.getType() != IResource.FILE)
			return false;
		GitRepository repo = getGitRepositoryManager().getAttached(resource.getProject());
		if (repo == null)
			return false;
		IPath resourcePath = repo.relativePath(resource);
		if (resourcePath == null)
			return false;
		return true;
	}
}
