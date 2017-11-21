package com.github.tdurieux.repair.maven.plugin;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.surefire.log.api.NullConsoleLogger;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.surefire.report.ReportTestSuite;
import org.apache.maven.plugins.surefire.report.SurefireReportParser;
import org.apache.maven.project.MavenProject;
import org.apache.maven.reporting.MavenReportException;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public abstract class AbstractRepairMojo extends AbstractMojo {

    @Parameter(property = "java.version", defaultValue = "-1")
    private String javaVersion;

    @Parameter(property = "maven.compiler.source", defaultValue = "-1")
    private String source;

    @Parameter(property = "maven.compile.source", defaultValue = "-1")
    private String oldSource;

    @Component
    protected ArtifactFactory artifactFactory;

    @Parameter(defaultValue="${localRepository}")
    protected ArtifactRepository localRepository;

    @Parameter(defaultValue="${project}", readonly=true, required=true)
    private MavenProject project;

    @Parameter( defaultValue = "${reactorProjects}", readonly = true )
	protected List<MavenProject> reactorProjects;

    public int getComplianceLevel() {
        int complianceLevel = 7;
        if (!source.equals("-1")) {
            complianceLevel = Integer.parseInt(source.substring(2));
        } else if (!oldSource.equals("-1")) {
            complianceLevel = Integer.parseInt(oldSource.substring(2));
        } else if (!javaVersion.equals("-1")) {
            complianceLevel = Integer.parseInt(javaVersion.substring(2, 3));
        }
        return complianceLevel;
    }

        private File getSurefireReportsDirectory( MavenProject subProject ) {
        String buildDir = subProject.getBuild().getDirectory();
        return new File( buildDir + "/surefire-reports" );
    }

    public List<String> getFailingTests() {
        List<String> result = new ArrayList<>();

        for (MavenProject mavenProject : reactorProjects) {
            File surefireReportsDirectory = getSurefireReportsDirectory(mavenProject);
            SurefireReportParser parser = new SurefireReportParser(Collections.singletonList(surefireReportsDirectory), Locale.ENGLISH, new NullConsoleLogger());

            try {
                List<ReportTestSuite> testSuites = parser.parseXMLReportFiles();
                for (ReportTestSuite reportTestSuite : testSuites) {
                    if (reportTestSuite.getNumberOfErrors()+reportTestSuite.getNumberOfFailures() > 0) {
                        result.add(reportTestSuite.getFullClassName());
                    }
                }
            } catch (MavenReportException e) {
                e.printStackTrace();;
            }

        }

        return result;
    }

    public List<URL> getClasspath() {
        List<URL> classpath = new ArrayList<>();
        for (MavenProject mavenProject : reactorProjects) {
            try {
                for (String s : (List<String>)mavenProject.getTestClasspathElements()) {
                    File f = new File(s);
                    classpath.add(f.toURI().toURL());
                }
            } catch (DependencyResolutionRequiredException e) {
                continue;
            } catch (MalformedURLException e) {
                e.printStackTrace();
            }
        }
        return new ArrayList<>(classpath);
    }

	public List<String> getTestFolders() {
		Set<String> sourceFolder = new HashSet<String>();
		for (MavenProject mavenProject : reactorProjects) {
			File sourceDirectory = new File(mavenProject.getBuild().getTestSourceDirectory());
			if (sourceDirectory.exists()) {
				sourceFolder.add(sourceDirectory.getAbsolutePath());
			}
		}
		return new ArrayList<>(sourceFolder);
	}

    public List<File> getSourceFolders() {
        Set<File> sourceFolder = new HashSet<>();
        for (MavenProject mavenProject : reactorProjects) {
            File sourceDirectory = new File(mavenProject.getBuild().getSourceDirectory());
            if (sourceDirectory.exists()) {
                sourceFolder.add(sourceDirectory);
            }

            File generatedSourceDirectory = new File(mavenProject.getBuild().getOutputDirectory() + "/generated-sources");
            if (generatedSourceDirectory.exists()) {
                sourceFolder.add(generatedSourceDirectory);
            }
        }
        return new ArrayList<>(sourceFolder);
    }
}
