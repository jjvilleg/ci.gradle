/**
 * (C) Copyright IBM Corporation 2014, 2017.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.wasdev.wlp.gradle.plugins.tasks

import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.gradle.api.GradleException
import groovy.util.XmlParser
import groovy.lang.Tuple
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.PublishArtifact
import org.gradle.plugins.ear.descriptor.EarModule
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.DependencySet
import org.apache.commons.io.FilenameUtils
import org.gradle.api.file.FileCollection
import org.w3c.dom.Element;
import java.util.regex.Pattern
import java.util.regex.Matcher
import java.text.MessageFormat
import org.apache.commons.io.FilenameUtils

import org.gradle.api.Task
import org.gradle.api.tasks.bundling.War
import org.gradle.plugins.ear.Ear
import net.wasdev.wlp.gradle.plugins.utils.*

class InstallAppsTask extends AbstractServerTask {

    protected ApplicationXmlDocument applicationXml = new ApplicationXmlDocument();

    @TaskAction
    void installApps() {
        if ((server.apps == null || server.apps.isEmpty()) && (server.dropins == null || server.dropins.isEmpty())) {
            if (project.plugins.hasPlugin('war')) {
                server.apps = [project.war]
            }
        }
        if (server.apps != null && !server.apps.isEmpty()) {
            Tuple appsLists = splitAppList(server.apps)
            installMultipleApps(appsLists[0], 'apps')
            installFileList(appsLists[1], 'apps')
        }
        if (server.dropins != null && !server.dropins.isEmpty()) {
            Tuple dropinsLists = splitAppList(server.dropins)
            installMultipleApps(dropinsLists[0], 'dropins')
            installFileList(dropinsLists[1], 'dropins')
        }
        if (applicationXml.hasChildElements()) {
            logger.warn("At least one application is not defined in the server configuration but the build file indicates it should be installed in the apps folder. Application configuration is being added to the target server configuration dropins folder by the plug-in.");
            applicationXml.writeApplicationXmlDocument(getServerDir(project));
        } else {
            if (ApplicationXmlDocument.getApplicationXmlFile(getServerDir(project)).exists()) {
                ApplicationXmlDocument.getApplicationXmlFile(getServerDir(project)).delete();
            }
        }
    }

    private void installMultipleApps(List<Task> applications, String appsDir) {
        applications.each{ Task task ->
          installProject(task, appsDir)
        }
    }

    private void installProjectArchive(Task task, String appsDir){
      Files.copy(task.archivePath.toPath(), new File(getServerDir(project), "/" + appsDir + "/" + getArchiveName(task)).toPath(), StandardCopyOption.REPLACE_EXISTING)
      validateAppConfig(getArchiveName(task), task.baseName, appsDir)
    }

    protected void validateAppConfig(String fileName, String artifactId, String dir) throws Exception {
        String appsDir = dir
        if (appsDir.equalsIgnoreCase('apps') && !isAppConfiguredInSourceServerXml(fileName)) {
            applicationXml.createApplicationElement(fileName, artifactId)
        }
        else if (appsDir.equalsIgnoreCase('dropins') && isAppConfiguredInSourceServerXml(fileName)) {
            throw new GradleException("The application, " + artifactId + ", is configured in the server.xml and the plug-in is configured to install the application in the dropins folder. A configured application must be installed to the apps folder.")
        }
    }

    protected boolean isAppConfiguredInSourceServerXml(String fileName) {
        boolean configured = false;
        File serverConfigFile = new File(getServerDir(project), 'server.xml')
        if (serverConfigFile != null && serverConfigFile.exists()) {
            try {
                ServerConfigDocument scd = new ServerConfigDocument(serverConfigFile, server.configDirectory, server.bootstrapPropertiesFile, server.bootstrapProperties, server.serverEnv)
                if (scd != null && scd.getLocations().contains(fileName)) {
                    logger.debug("Application configuration is found in server.xml : " + fileName)
                    configured = true
                }
            }
            catch (Exception e) {
                logger.warn(e.getLocalizedMessage())
            }
        }
        return configured
    }

    protected String getArchiveName(Task task){
        if (server.stripVersion){
            return task.baseName + "." + task.extension
        }
        return task.archiveName;
    }

    protected void installProject(Task task, String appsDir) throws Exception {
      if(isSupportedType()) {
        if(server.looseApplication){
          installLooseApplication(task, appsDir)
        } else {
          installProjectArchive(task, appsDir)
        }
      } else {
        throw new GradleException(MessageFormat.format("Application {0} is not supported", task.archiveName))
      }
    }

    private void installLooseApplication(Task task, String appsDir) throws Exception {
      String looseConfigFileName = getLooseConfigFileName(task)
      String application = looseConfigFileName.substring(0, looseConfigFileName.length()-4)
      File destDir = new File(getServerDir(project), appsDir)
      File looseConfigFile = new File(destDir, looseConfigFileName)
      LooseConfigData config = new LooseConfigData()
      switch(getPackagingType()){
        case "war":
            validateAppConfig(application, task.baseName, appsDir)
            logger.info(MessageFormat.format(("Installing application into the {0} folder."), looseConfigFile.getAbsolutePath()))
            installLooseConfigWar(config, task)
            deleteApplication(new File(getServerDir(project), "apps"), looseConfigFile)
            deleteApplication(new File(getServerDir(project), "dropins"), looseConfigFile)
            config.toXmlFile(looseConfigFile)
            break
        case "ear":
            validateAppConfig(application, task.baseName, appsDir)
            logger.info(MessageFormat.format(("Installing application into the {0} folder."), looseConfigFile.getAbsolutePath()))
            installLooseConfigEar(config, task)
            deleteApplication(new File(getServerDir(project), "apps"), looseConfigFile)
            deleteApplication(new File(getServerDir(project), "dropins"), looseConfigFile)
            config.toXmlFile(looseConfigFile)
            break
        default:
            logger.info(MessageFormat.format(("Loose application configuration is not supported for packaging type {0}. The project artifact will be installed as an archive file."),
                    project.getPackaging()))
            installProjectArchive(task, appsDir)
            break
        }
    }

    protected void installLooseConfigWar(LooseConfigData config, Task task) throws Exception {
        Task compileJava = task.getProject().tasks.findByPath(':compileJava')

        File outputDir;
        if(compileJava != null){
            outputDir = compileJava.destinationDir
        }

        if (outputDir != null && !outputDir.exists() && hasJavaSourceFiles(task.classpath, outputDir)) {
          logger.warn(MessageFormat.format("Installed loose application from project {0}, but the project has not been compiled.", project.name))
        }
        LooseWarApplication looseWar = new LooseWarApplication(task, config)
        looseWar.addSourceDir()
        looseWar.addOutputDir(looseWar.getDocumentRoot() , task, "/WEB-INF/classes/");

        //retrieves dependent library jar files
        addWarEmbeddedLib(looseWar.getDocumentRoot(), looseWar, task);

        //add Manifest file
        File manifestFile = new File(project.sourceSets.main.getOutput().getResourcesDir().getParentFile().getAbsolutePath() + "/META-INF/MANIFEST.MF")
        looseWar.addManifestFile(manifestFile, "gradle-war-plugin")
    }

    private boolean hasJavaSourceFiles(FileCollection classpath, File outputDir){
        for(File f: classpath) {
            if(f.getAbsolutePath().equals(outputDir.getCanonicalPath())) {
                return true;
            }
        }
        return false;
    }

    private void addWarEmbeddedLib(Element parent, LooseWarApplication looseApp, Task task) throws Exception {
      ArrayList<File> deps = new ArrayList<File>();
      task.classpath.each {deps.add(it)}
      //Removes WEB-INF/lib/main directory since it is not rquired in the xml
      if(deps != null && !deps.isEmpty()) {
        deps.remove(0)
      }
      File parentProjectDir = new File(task.getProject().getRootProject().rootDir.getAbsolutePath())
      for (File dep: deps) {
        String dependentProjectName = "project ':"+getProjectPath(parentProjectDir, dep)+"'"
        Project siblingProject = project.getRootProject().findProject(dependentProjectName)
        boolean isCurrentProject = ((task.getProject().toString()).equals(dependentProjectName))
        if (!isCurrentProject && siblingProject != null){
            Element archive = looseApp.addArchive(parent, "/WEB-INF/lib/"+ dep.getName());
            looseApp.addOutputDirectory(archive, siblingProject, "/");
            Task resourceTask = siblingProject.getTasks().findByPath(":"+dependentProjectName+":processResources");
            if (resourceTask.getDestinationDir() != null){
                looseApp.addOutputDir(archive, resourceTask.getDestinationDir(), "/");
            }
            looseApp.addManifestFile(archive, siblingProject, "gradle-jar-plugin");
        } else if(FilenameUtils.getExtension(dep.getAbsolutePath()).equalsIgnoreCase("jar")){
            looseApp.getConfig().addFile(parent, dep.getAbsolutePath() , "/WEB-INF/lib/" + dep.getName());
        } else {
            looseApp.addOutputDir(looseApp.getDocumentRoot(), dep.getAbsolutePath() , "/WEB-INF/classes/");
        }
      }
    }

    protected void installLooseConfigEar(LooseConfigData config, Task task) throws Exception{
        LooseEarApplication looseEar = new LooseEarApplication(task, config);
        looseEar.addSourceDir();
        looseEar.addApplicationXmlFile();
        //ArrayList<EarModule> artifacts = new ArrayList<EarModule>();
        File[] filesAsDeps = task.getProject().configurations.deploy.getFiles().toArray()
        Dependency[] deps = task.getProject().configurations.deploy.getAllDependencies().toArray()
        HashMap<File, Dependency> completeDeps = new HashMap<File, Dependency>();
        if(filesAsDeps.size() == deps.size()){
            for(int i = 0; i<filesAsDeps.size(); i++) {
                completeDeps.put(filesAsDeps[i], deps[i])
            }
        }

        logger.info(MessageFormat.format("Number of compile dependencies for " + task.project.name + " : " + completeDeps.size()))
        for (Map.Entry<File, Dependency> entry : completeDeps){
            Dependency dependency = entry.getValue();
            File dependancyFile = entry.getKey();
            
            if (dependency instanceof ProjectDependency) {
                Project dependencyProject = dependency.getDependencyProject()
                String projectType = FilenameUtils.getExtension(dependancyFile.toString())
                switch (projectType) {
                        case "jar":
                            looseEar.addJarModule(dependencyProject, dependency);
                            break;
                        case "ejb":
                            //looseEar.addEjbModule(dependencyProject);
                            break;
                        case "war":
                            println("\n\n\n::::: it should be adding the warArchive")
                            Element warArchive = looseEar.addWarModule(dependencyProject,
                                        "TempElement till I figure Out", dependency);
                            addEmbeddedLib(warArchive, dependencyProject, looseEar, "/WEB-INF/lib/");
                            break;
                        default:
                            // use the artifact from local .m2 repo
                            //looseEar.addModuleFromM2(resolveArtifact(artifact));
                            println("not supported type!!")
                            break;
                    }
            }
            else if (dependency instanceof ExternalModuleDependency) {
                String dependencyName = dependency.getName() + "-" + dependency.getVersion()
                task.getProject().configurations.deploy.getFiles().each {
                    if( dependencyName.equals(FilenameUtils.removeExtension(it.getName()))) {
                        looseEar.getConfig().addFile(it.getAbsolutePath(), "/WEB-INF/lib/" + it.getName())
                    }
                }
            }
            else if (dependency instanceof ModuleDependency){
                println("Module Dependency!!!" + d)
            }
        }
        File manifestFile = new File(project.sourceSets.main.getOutput().getResourcesDir().getParentFile().getAbsolutePath() + "/META-INF/MANIFEST.MF")
        looseEar.addManifestFile(manifestFile, "gradle-ear-plugin")
    }
    private void addEmbeddedLib(Element parent, Project proj, LooseApplication looseApp, String dir) throws Exception {
        ArrayList<Dependency> deps = new ArrayList<Dependency>();
        proj.configurations.providedRuntime.getAllDependencies().each {deps.add(it)}
        //Removes WEB-INF/lib/main directory since it is not rquired in the xml
        if(deps != null && !deps.isEmpty()) {
          deps.remove(0)
        }
        for (Dependency dep : deps){
            addlibrary(parent, looseApp, dir, dep);
        }
    }
    private void addlibrary(Element parent, LooseApplication looseApp, String dir, Dependency dependency)
            throws Exception {
            if (dependency instanceof ProjectDependency) {
                println("\n\n\n::::: Its actually going through the project Dependency?!?!")
                Project dependProject = dependency.getDependencyProject()
                Element archive = looseApp.addArchive(parent, dir + dependProject.sourceSets.main.getOutput().getResourcesDir().getParentFile().getAbsolutePath() + ".jar");
                //looseApp.addOutputDir(archive, dependProject, "/");
                //looseApp.addManifestFile(archive, dependProject, "gradle-jar-plugin");
            } else {
                //resolveArtifact(artifact);
                println("\n\n\n::::: It should be going through this else!")
                looseApp.getConfig().addFile(parent, "artifact.getFile().getAbsolutePath()",
                        dir + "artifact.getFile().getName()");
            }
    }

    private String getProjectPath(File parentProjectDir, File dep) {
      String dependencyPathPortion = dep.getAbsolutePath().replace(parentProjectDir.getAbsolutePath()+"/","")
      String projectPath = dep.getAbsolutePath().replace(dependencyPathPortion,"")
      Pattern pattern = Pattern.compile("/build/.*")
      Matcher matcher = pattern.matcher(dependencyPathPortion)
      projectPath = matcher.replaceAll("")
      return projectPath;
    }

    private boolean isSupportedType(){
      switch (getPackagingType()) {
        case "ear":
        case "war":
            return true;
        default:
            return false;
        }
    }
    private String getLooseConfigFileName(Task task){
      return getArchiveName(task) + ".xml"
    }

    //Cleans up the application if the install style is switched from loose application to archive and vice versa
    protected void deleteApplication(File parent, File artifactFile) throws IOException {
        deleteApplication(parent, artifactFile.getName());
        if (artifactFile.getName().endsWith(".xml")) {
            deleteApplication(parent, artifactFile.getName().substring(0, artifactFile.getName().length() - 4));
        } else {
            deleteApplication(parent, artifactFile.getName() + ".xml");
        }
    }

    protected void deleteApplication(File parent, String filename) throws IOException {
        File application = new File(parent, filename);
        if (application.isDirectory()) {
            FileUtils.deleteDirectory(application);
        } else {
            application.delete();
        }
    }

    protected void installFromFile(File file, String appsDir) {
        Files.copy(file.toPath(), new File(getServerDir(project).toString() + '/' + appsDir + '/' + file.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
        validateAppConfig(file.name, file.name.take(file.name.lastIndexOf('.')), appsDir)
    }

    protected void installFileList(List<File> appFiles, String appsDir) {
        appFiles.each { File appFile ->
            installFromFile(appFile, appsDir)
        }
    }

    private Tuple splitAppList(List<Object> allApps) {
        List<File> appFiles = new ArrayList<File>()
        List<Task> appTasks = new ArrayList<Task>()

        allApps.each { Object appObj ->
            if (appObj instanceof Task) {
                appTasks.add((Task)appObj)
            } else if (appObj instanceof File) {
                appFiles.add((File)appObj)
            } else {
                logger.warn('Application ' + appObj.getClass.name + ' is expressed as ' + appObj.toString() + ' which is not a supported input type. Define applications using Task or File objects.')
            }
        }

        return new Tuple(appTasks, appFiles)
    }
}
