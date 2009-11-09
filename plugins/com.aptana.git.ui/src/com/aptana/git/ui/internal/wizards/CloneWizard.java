package com.aptana.git.ui.internal.wizards;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.ui.IImportWizard;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.internal.ide.StatusUtil;
import org.eclipse.ui.statushandlers.StatusManager;

import com.aptana.git.core.model.GitExecutable;
import com.aptana.git.ui.GitUIPlugin;
import com.aptana.git.ui.internal.Launcher;
import com.aptana.git.ui.internal.sharing.ConnectProviderOperation;

public class CloneWizard extends Wizard implements IImportWizard
{
	/**
	 * The name of the folder containing metadata information for the workspace.
	 */
	public static final String METADATA_FOLDER = ".metadata"; //$NON-NLS-1$

	private RepositorySelectionPage cloneSource;

	@Override
	public boolean performFinish()
	{
		final String sourceURI = cloneSource.getSource();
		final String dest = cloneSource.getDestination();
		Job job = new Job("Cloning git repo")
		{
			@Override
			protected IStatus run(IProgressMonitor monitor)
			{
				SubMonitor subMonitor = SubMonitor.convert(monitor, 200);
				try
				{
					ILaunch launch = Launcher.launch(GitExecutable.instance().path(), null, "clone", sourceURI, dest);
					while (!launch.isTerminated())
					{
						if (subMonitor.isCanceled())
							return Status.CANCEL_STATUS;
						Thread.yield();
					}
					subMonitor.worked(100);
					// Search the children of the repo for existing projects!
					Collection<File> existingProjects = collectProjectFilesFromDirectory(new File(dest), null,
							subMonitor.newChild(25));
					if (existingProjects.isEmpty())
					{ // No projects found. Turn the root of the repo into a project!
						String projectName = dest;
						if (projectName.lastIndexOf(File.separator) != -1)
						{
							projectName = projectName.substring(projectName.lastIndexOf(File.separator) + 1);
						}
						ProjectRecord record = new ProjectRecord(new File(dest));
						record.projectName = projectName;
						createExistingProject(record, subMonitor.newChild(75));
					}
					else
					{
						// TODO Should probably prompt user which projects to import
						int step = 75 / existingProjects.size();
						for (File file : existingProjects)
						{
							createExistingProject(new ProjectRecord(file), subMonitor.newChild(step));
						}
					}
				}
				catch (InvocationTargetException e)
				{
					return new Status(IStatus.ERROR, GitUIPlugin.getPluginId(), e.getMessage(), e);
				}
				catch (InterruptedException e)
				{
					return new Status(IStatus.ERROR, GitUIPlugin.getPluginId(), e.getMessage(), e);
				}
				finally
				{
					subMonitor.done();
				}
				return Status.OK_STATUS;
			}
		};
		job.setUser(true);
		job.schedule();
		return true;
	}

	/**
	 * Collect the list of .project files that are under directory into files.
	 * 
	 * @param directory
	 * @param directoriesVisited
	 *            Set of canonical paths of directories, used as recursion guard
	 * @param monitor
	 *            The monitor to report to
	 * @return boolean <code>true</code> if the operation was completed.
	 */
	private Collection<File> collectProjectFilesFromDirectory(File directory, Set<String> directoriesVisited,
			IProgressMonitor monitor)
	{

		if (monitor.isCanceled())
		{
			return Collections.emptyList();
		}
		// monitor.subTask(NLS.bind(
		// UIText.WizardProjectsImportPage_CheckingMessage, directory
		// .getPath()));
		File[] contents = directory.listFiles();
		if (contents == null)
			return Collections.emptyList();

		Collection<File> files = new HashSet<File>();

		// Initialize recursion guard for recursive symbolic links
		if (directoriesVisited == null)
		{
			directoriesVisited = new HashSet<String>();
			try
			{
				directoriesVisited.add(directory.getCanonicalPath());
			}
			catch (IOException exception)
			{
				StatusManager.getManager().handle(
						StatusUtil.newStatus(IStatus.ERROR, exception.getLocalizedMessage(), exception));
			}
		}

		// first look for project description files
		final String dotProject = IProjectDescription.DESCRIPTION_FILE_NAME;
		for (int i = 0; i < contents.length; i++)
		{
			File file = contents[i];
			if (file.isFile() && file.getName().equals(dotProject))
			{
				files.add(file);
				// don't search sub-directories since we can't have nested
				// projects
				return files;
			}
		}
		// no project description found, so recurse into sub-directories
		for (int i = 0; i < contents.length; i++)
		{
			if (contents[i].isDirectory())
			{
				if (!contents[i].getName().equals(METADATA_FOLDER))
				{
					try
					{
						String canonicalPath = contents[i].getCanonicalPath();
						if (!directoriesVisited.add(canonicalPath))
						{
							// already been here --> do not recurse
							continue;
						}
					}
					catch (IOException exception)
					{
						StatusManager.getManager().handle(
								StatusUtil.newStatus(IStatus.ERROR, exception.getLocalizedMessage(), exception));

					}
					files.addAll(collectProjectFilesFromDirectory(contents[i], directoriesVisited, monitor));
				}
			}
		}
		return files;
	}

	/**
	 * Create the project described in record. If it is successful return true.
	 * 
	 * @param record
	 * @param monitor
	 * @return boolean <code>true</code> if successful
	 * @throws InvocationTargetException
	 * @throws InterruptedException
	 */
	private boolean createExistingProject(final ProjectRecord record, IProgressMonitor monitor)
			throws InvocationTargetException, InterruptedException
	{
		String projectName = record.getProjectName();
		final IWorkspace workspace = ResourcesPlugin.getWorkspace();
		final IProject project = workspace.getRoot().getProject(projectName);
		if (record.description == null)
		{
			// error case
			record.description = workspace.newProjectDescription(projectName);
			IPath locationPath = new Path(record.projectSystemFile.getAbsolutePath());

			// If it is under the root use the default location
			if (Platform.getLocation().isPrefixOf(locationPath))
			{
				record.description.setLocation(null);
			}
			else
			{
				record.description.setLocation(locationPath);
			}
		}
		else
		{
			record.description.setName(projectName);
		}

		try
		{
			// monitor.beginTask(
			// UIText.WizardProjectsImportPage_CreateProjectsTask, 100);
			project.create(record.description, new SubProgressMonitor(monitor, 30));
			project.open(IResource.BACKGROUND_REFRESH, new SubProgressMonitor(monitor, 50));

			ConnectProviderOperation connectProviderOperation = new ConnectProviderOperation(project);
			connectProviderOperation.run(new SubProgressMonitor(monitor, 20));

		}
		catch (CoreException e)
		{
			throw new InvocationTargetException(e);
		}
		finally
		{
			monitor.done();
		}

		return true;
	}

	public void init(IWorkbench workbench, IStructuredSelection selection)
	{
		cloneSource = new RepositorySelectionPage();
	}

	@Override
	public void addPages()
	{
		addPage(cloneSource);
	}

	class ProjectRecord
	{

		File projectSystemFile;
		String projectName;
		IProjectDescription description;

		/**
		 * Create a record for a project based on the info in the file.
		 * 
		 * @param file
		 */
		ProjectRecord(File file)
		{
			projectSystemFile = file;
			setProjectName();
		}

		/**
		 * Set the name of the project based on the projectFile.
		 */
		private void setProjectName()
		{
			try
			{
				// If we don't have the project name try again
				if (projectName == null)
				{
					IPath path = new Path(projectSystemFile.getPath());
					// if the file is in the default location, use the directory
					// name as the project name
					if (isDefaultLocation(path))
					{
						projectName = path.segment(path.segmentCount() - 2);
						description = ResourcesPlugin.getWorkspace().newProjectDescription(projectName);
					}
					else
					{
						description = ResourcesPlugin.getWorkspace().loadProjectDescription(path);
						projectName = description.getName();
					}

				}
			}
			catch (CoreException e)
			{
				// no good couldn't get the name
			}
		}

		/**
		 * Returns whether the given project description file path is in the default location for a project
		 * 
		 * @param path
		 *            The path to examine
		 * @return Whether the given path is the default location for a project
		 */
		private boolean isDefaultLocation(IPath path)
		{
			// The project description file must at least be within the project,
			// which is within the workspace location
			if (path.segmentCount() < 2)
				return false;
			return path.removeLastSegments(2).toFile().equals(Platform.getLocation().toFile());
		}

		/**
		 * Get the name of the project
		 * 
		 * @return String
		 */
		public String getProjectName()
		{
			return projectName;
		}
	}
}
