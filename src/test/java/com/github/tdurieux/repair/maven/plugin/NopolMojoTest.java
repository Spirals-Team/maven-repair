package com.github.tdurieux.repair.maven.plugin;

import fr.inria.lille.repair.nopol.NopolStatus;
import org.apache.maven.plugin.Mojo;

import java.io.File;

public class NopolMojoTest extends BetterAbstractMojoTestCase {

	private final String projectPath = "src/test/resources/projects/example1/";

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		Process mvn_clean_test = Runtime.getRuntime().exec("mvn clean test", null,	new File(projectPath));
		mvn_clean_test.waitFor();
	}

	@Override
	protected void tearDown() throws Exception {
		super.tearDown();
		Process mvn_clean = Runtime.getRuntime().exec("mvn clean", null,	new File(projectPath));
		mvn_clean.waitFor();
	}

	public void testNopolRepair() throws Exception {
		File f = getTestFile(projectPath + "pom.xml");
		Mojo mojo = lookupConfiguredMojo(f, "nopol");
		assertNotNull( mojo );
		assertTrue("Wrong class: "+mojo, mojo instanceof NopolMojo);

		NopolMojo repair = (NopolMojo) mojo;
		repair.execute();

		assertEquals(NopolStatus.PATCH, repair.getResult().getNopolStatus());
	}
}