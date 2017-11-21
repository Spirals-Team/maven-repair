package com.github.tdurieux.repair.maven.plugin;

import org.apache.maven.plugin.Mojo;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;

import java.io.File;

public class NopolMojoTest extends BetterAbstractMojoTestCase {


	@Override
	protected void setUp() throws Exception {
		super.setUp();
		ProjectBuilder projectBuilder = lookup(ProjectBuilder.class);
		ProjectBuildingRequest projectBuildingRequest = newMavenSession().getProjectBuildingRequest();
		ProjectBuildingResult build = projectBuilder.build(getTestFile(
				"src/test/resources/projects/example1/pom.xml"),
				projectBuildingRequest);
		MavenProject project = build.getProject();
		Process mvn_clean_test = Runtime.getRuntime().exec("mvn clean test", null,	new File("src/test/resources/projects/example1/"));
		mvn_clean_test.waitFor();
	}

	@Override
	protected void tearDown() throws Exception {
		super.tearDown();
		Process mvn_clean = Runtime.getRuntime().exec("mvn clean", null,	new File("src/test/resources/projects/example1/"));
		mvn_clean.waitFor();
	}

	public void testNopolRepair() throws Exception {
		File f = getTestFile("src/test/resources/projects/example1/pom.xml");
		Mojo mojo = lookupConfiguredMojo(f, "nopol");
		assertNotNull( mojo );
		assertTrue("Wrong class: "+mojo, mojo instanceof NopolMojo);

		NopolMojo repair = (NopolMojo) mojo;
		repair.execute();
	}
}