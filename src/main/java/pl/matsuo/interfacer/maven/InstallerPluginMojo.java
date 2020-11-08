package pl.matsuo.interfacer.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.apache.commons.io.IOUtils.toByteArray;

/**
 * Build uber jar from specified artifact and all it's dependencies. This plugin is not a part of
 * project's build process. It is intended to build merged jars and executable jars from artifact
 * which is already published to repository.
 *
 * <p>Example:
 *
 * <pre>
 * mvn pl.matsuo.installer:installer-maven-plugin:install \
 *   -DgroupId=[groupId] \
 *   -DartifactId=[artifactId] \
 *   -Dversion=[version]
 * </pre>
 *
 * Should generate two files:
 *
 * <pre>
 *     [artifactId]_[version].jar // merged jar
 *     [artifactId]_[version]     // merged jar runnable from bash (assuming MANIFEST.MF is in jar
 *                                // and it contains main class definition)
 * </pre>
 */
@Mojo(name = "install", requiresProject = false)
public class InstallerPluginMojo extends AbstractMojo {

  public static final String META_INF_MANIFEST_MF = "META-INF/MANIFEST.MF";
  @Component private RepositorySystem repoSystem;

  /** The current repository/network configuration of Maven. */
  @Parameter(defaultValue = "${repositorySystemSession}")
  private RepositorySystemSession repoSession;

  /** The project's remote repositories to use for the resolution of project dependencies. */
  @Parameter(defaultValue = "${project.remoteProjectRepositories}")
  private List<RemoteRepository> projectRepos;

  /** groupId of artifact that should be processed */
  @Parameter(property = "groupId", required = true, readonly = true)
  String groupId;

  /** artifactId of artifact that should be processed */
  @Parameter(property = "artifactId", required = true, readonly = true)
  String artifactId;

  /** version of artifact that should be processed */
  @Parameter(property = "version", readonly = true)
  String version;

  /**
   * Custom name for jar and executable file. If not specified, name will be derived from artifactId
   * and version.
   */
  @Parameter(property = "resultFileName", readonly = true)
  String resultFileName;

  /** Create merged jar and executable jar. */
  @Override
  public void execute() throws MojoExecutionException {
    getLog().info("Install app " + groupId + ":" + artifactId + ":" + version);

    // resolve dependencies of application
    DependencyResult dependencyResult = resolveDependencies();
    getLog()
        .info(
            "Root artifact: "
                + dependencyResult.getRoot().getArtifact().getFile().getAbsolutePath());

    // build merged zip file
    Map<String, Pair<JarEntry, byte[]>> files = collectConstituentFiles(dependencyResult.getRoot());

    // remove problematic files
    filterFiles(files);

    // create executable jar
    createJar(files);

    // create ~/bin/<artifact_name>
    createExecutable();
  }

  /** Exclude files that cannot be included in final jar for some reason. */
  private void filterFiles(Map<String, Pair<JarEntry, byte[]>> files) {
    Iterator<Map.Entry<String, Pair<JarEntry, byte[]>>> iterator = files.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<String, Pair<JarEntry, byte[]>> next = iterator.next();
      String path = next.getKey();
      // <exclude>META-INF/*.SF</exclude>
      // <exclude>META-INF/*.DSA</exclude>
      // <exclude>META-INF/*.RSA</exclude>
      if (path.startsWith("META-INF/")
          && (path.endsWith(".SF") || path.endsWith(".DSA") || path.endsWith(".RSA"))) {
        getLog().debug("Removing file from final jar " + path);
        iterator.remove();
      }
    }
  }

  /** Create executable file. */
  private void createExecutable() {
    try {
      File executable = new File(getOutputFileName());
      if (!executable.exists()) {
        executable.createNewFile();
      }

      FileOutputStream outputStream = new FileOutputStream(executable);

      outputStream.write(toByteArray(getClass().getResourceAsStream("/executable_prefix.sh")));
      outputStream.write(toByteArray(new FileInputStream(getOutputFileName() + ".jar")));

      outputStream.close();

      getLog().info("Created executable: " + getOutputFileName());
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /** Create jar from merging artifact and all its dependencies. */
  private void createJar(Map<String, Pair<JarEntry, byte[]>> files) throws MojoExecutionException {
    try {
      Manifest manifest = createManifest(files);
      String fileName = getOutputFileName() + ".jar";
      JarOutputStream target = new JarOutputStream(new FileOutputStream(fileName), manifest);

      for (Map.Entry<String, Pair<JarEntry, byte[]>> entry : files.entrySet()) {
        addJarEntry(entry.getKey(), entry.getValue().getKey(), entry.getValue().getValue(), target);
      }

      target.close();

      getLog().info("Created merged jar: " + fileName);
    } catch (IOException e) {
      throw new MojoExecutionException("Could not create result jar", e);
    }
  }

  /** Create manifest file for new jar. */
  private Manifest createManifest(Map<String, Pair<JarEntry, byte[]>> files) throws IOException {
    Manifest manifest;
    if (files.containsKey(META_INF_MANIFEST_MF)) {
      manifest = new Manifest(new ByteArrayInputStream(files.get(META_INF_MANIFEST_MF).value));
      manifest.getMainAttributes().remove(Attributes.Name.SIGNATURE_VERSION);
      files.remove(META_INF_MANIFEST_MF);
    } else {
      manifest = new Manifest();
      manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    }
    return manifest;
  }

  /** Add next entry to final jar. */
  private void addJarEntry(String path, JarEntry oldEntry, byte[] content, JarOutputStream target)
      throws IOException {
    JarEntry entry = new JarEntry(path);
    entry.setTime(oldEntry.getTime());
    entry.setLastModifiedTime(oldEntry.getLastModifiedTime());

    target.putNextEntry(entry);

    if (!oldEntry.isDirectory()) {
      target.write(content);
    }

    target.closeEntry();
  }

  /** Return base name of created files. */
  private String getOutputFileName() {
    if (resultFileName != null && !resultFileName.isEmpty()) {
      return resultFileName;
    } else {
      return artifactId + (version != null ? "_" + version : "");
    }
  }

  /** Create map containing all files that should be put into final jar. */
  private Map<String, Pair<JarEntry, byte[]>> collectConstituentFiles(DependencyNode root)
      throws MojoExecutionException {
    Map<String, Pair<JarEntry, byte[]>> result = new HashMap<>();

    // dependencies first
    for (DependencyNode child : root.getChildren()) {
      result.putAll(collectConstituentFiles(child));
    }

    // now the node itself (it will overwrite dependencies files if duplicates exist)
    try {
      processJarFile(root, result);
    } catch (IOException e) {
      throw new MojoExecutionException(
          "Could not process file " + root.getArtifact().getFile().getAbsolutePath(), e);
    }

    return result;
  }

  /** Collect jar entries from single file. */
  private void processJarFile(DependencyNode root, Map<String, Pair<JarEntry, byte[]>> result)
      throws IOException {
    JarFile jarFile = new JarFile(root.getArtifact().getFile());
    Enumeration<JarEntry> entries = jarFile.entries();
    while (entries.hasMoreElements()) {
      JarEntry jarEntry = entries.nextElement();
      byte[] bytes;
      if (jarEntry.isDirectory()) {
        bytes = null;
      } else {
        InputStream inputStream = jarFile.getInputStream(jarEntry);
        bytes = toByteArray(inputStream);
      }
      result.put(jarEntry.getName(), new Pair<>(jarEntry, bytes));
    }
  }

  /** Resolve all dependencies of specified artifact. */
  private DependencyResult resolveDependencies() throws MojoExecutionException {
    try {
      Artifact artifact = new DefaultArtifact(groupId, artifactId, "jar", version);
      Dependency dependency = new Dependency(artifact, "compile");
      CollectRequest collectRequest = new CollectRequest(dependency, projectRepos);
      DependencyRequest dependencyRequest = new DependencyRequest(collectRequest, null);

      DependencyResult dependencyResult =
          repoSystem.resolveDependencies(repoSession, dependencyRequest);

      getLog().info("Dependencies resolution successful");
      logAllDependencies(dependencyResult);

      return dependencyResult;
    } catch (DependencyResolutionException e) {
      throw new MojoExecutionException("Could not resolve artifact ", e);
    }
  }

  /** Log all artifacts to debug stream. */
  private void logAllDependencies(DependencyResult dependencyResult) {
    dependencyResult
        .getArtifactResults()
        .forEach(
            result -> {
              getLog().debug("Artifact: " + result.toString());
              if (result.getArtifact().getFile() != null) {
                getLog().debug("  - file: " + result.getArtifact().getFile().getAbsolutePath());
              }
            });
  }
}
