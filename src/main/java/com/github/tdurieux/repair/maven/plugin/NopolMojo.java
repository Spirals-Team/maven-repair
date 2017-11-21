package com.github.tdurieux.repair.maven.plugin;

import com.google.common.io.ByteStreams;
import fr.inria.lille.commons.synthesis.smt.solver.SolverFactory;
import fr.inria.lille.repair.common.config.NopolContext;
import fr.inria.lille.repair.common.patch.Patch;
import fr.inria.lille.repair.common.synth.StatementType;
import fr.inria.lille.repair.nopol.NoPol;
import fr.inria.lille.repair.nopol.NopolResult;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Mojo( name = "nopol", aggregator = true,
        defaultPhase = LifecyclePhase.TEST,
        requiresDependencyResolution = ResolutionScope.TEST)
public class NopolMojo extends AbstractRepairMojo {

    private static String HARDCODED_NOPOL_VERSION = "0.2-SNAPSHOT";

    @Parameter( defaultValue = "${project.build.directory}/nopol", property = "outputDir", required = true )
    private File outputDirectory;

    @Parameter( defaultValue = "pre_then_cond", property = "type", required = true )
    private String type;

    @Parameter( defaultValue = "10", property = "maxTime", required = true )
    private int maxTime;

    @Parameter( defaultValue = "gzoltar", property = "localizer", required = true )
    private String localizer;

    @Parameter( defaultValue = "dynamoth", property = "synthesis", required = true )
    private String synthesis;

    @Parameter( defaultValue = "z3", property = "solver", required = true )
    private String solver;

	private NopolResult result;

	@Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final List<String> failingTestCases = getFailingTests();
        final List<URL> dependencies = getClasspath();
        final List<File> sourceFolders = getSourceFolders();

        System.out.println(failingTestCases.size() + " detected failing test classes. (" + StringUtils.join(failingTestCases,":") + ")");

        final List<URL> nopolClasspath = getNopolClasspath();
        final String systemClasspath = System.getProperty("java.class.path");

        final StringBuilder sb = new StringBuilder(systemClasspath);
        if (sb.lastIndexOf(":") != sb.length() - 1) {
            sb.append(":");
        }
        for (int i = 0; i < nopolClasspath.size(); i++) {
            URL url = nopolClasspath.get(i);
            if (systemClasspath.contains(url.getPath())) {
                continue;
            }
            sb.append(url.getPath());
            if (i < nopolClasspath.size() - 1) {
                sb.append(":");
            }
        }

        try {
            setGzoltarDebug(true);
            System.setProperty("java.class.path", sb.toString());
            NopolContext nopolContext = createNopolContext(failingTestCases, dependencies, sourceFolders);
            final NoPol nopol = new NoPol(nopolContext);
            this.result = nopol.build();
            printResults(result);
        } finally {
            System.setProperty("java.class.path", systemClasspath);
        }
    }

    private void printResults(NopolResult result) {
        System.out.println("Nopol executed after: "+result.getDurationInMilliseconds()+" ms.");
        System.out.println("Status: "+result.getNopolStatus());
        System.out.println("Angelic values: "+result.getNbAngelicValues());
        System.out.println("Nb statements: "+result.getNbStatements());
        if (result.getPatches().size() > 0) {
            for (Patch p : result.getPatches()) {
                System.out.println("Obtained patch: "+p.asString());
            }
        }
    }

    private NopolContext createNopolContext(List<String> failingTestCases,
            List<URL> dependencies, List<File> sourceFolders) {
        NopolContext nopolContext = new NopolContext(sourceFolders.toArray(new File[0]), dependencies.toArray(new URL[0]), failingTestCases.toArray(new String[0]), Collections.<String>emptyList());
        nopolContext.setComplianceLevel(getComplianceLevel());
        nopolContext.setTimeoutTestExecution(300);
        nopolContext.setMaxTimeEachTypeOfFixInMinutes(15);
        nopolContext.setMaxTimeInMinutes(maxTime);
        nopolContext.setLocalizer(this.resolveLocalizer());
        nopolContext.setSynthesis(this.resolveSynthesis());
        nopolContext.setType(this.resolveType());
        nopolContext.setOnlyOneSynthesisResult(true);
        nopolContext.setJson(true);
        if (!outputDirectory.exists()) {
			outputDirectory.mkdirs();
		}
        nopolContext.setOutputFolder(outputDirectory.getAbsolutePath());

        NopolContext.NopolSolver solver = this.resolveSolver();
        nopolContext.setSolver(solver);

        if (nopolContext.getSynthesis() == NopolContext.NopolSynthesis.SMT) {
            if (solver == NopolContext.NopolSolver.Z3) {
                String z3Path = this.loadZ3AndGivePath();
                SolverFactory.setSolver(solver, z3Path);
                nopolContext.setSolverPath(z3Path);
            } else {
                SolverFactory.setSolver(solver, null);
            }
        }
        return nopolContext;
    }

    private void setGzoltarDebug(boolean debugValue) {
        try {
            Field debug = com.gzoltar.core.agent.Launcher.class.getDeclaredField("debug");
            debug.setAccessible(true);
            debug.setBoolean(null, debugValue);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    private NopolContext.NopolSolver resolveSolver() {
        try {
            return NopolContext.NopolSolver.valueOf(solver.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Solver value \""+solver+"\" is wrong. Only following values are accepted: "+StringUtils.join(NopolContext.NopolSolver.values(),", "));
        }
    }

    private NopolContext.NopolLocalizer resolveLocalizer() {
        try {
            return NopolContext.NopolLocalizer.valueOf(localizer.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Localizer value \""+localizer+"\" is wrong. Only following values are accepted: "+StringUtils.join(NopolContext.NopolLocalizer.values(), ","));
        }
    }

    private NopolContext.NopolSynthesis resolveSynthesis() {
        try {
            return NopolContext.NopolSynthesis.valueOf(synthesis.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Synthesis value \""+synthesis+"\" is wrong. Only following values are accepted: "+StringUtils.join(NopolContext.NopolSynthesis.values(), ","));
        }
    }

    private StatementType resolveType() {
        try {
            return StatementType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Type value \""+type+"\" is wrong. Only following values are accepted: "+StringUtils.join(StatementType.values(), ","));
        }
    }

    private String loadZ3AndGivePath() {
        boolean isMac = System.getProperty("os.name").toLowerCase().contains("mac");

        String resourcePath = (isMac)? "z3/z3_for_mac" : "z3/z3_for_linux";
        InputStream in = this.getClass().getClassLoader().getResourceAsStream(resourcePath);

        try {
            Path tempFilePath = Files.createTempFile("nopol", "z3");
            byte[] content = ByteStreams.toByteArray(in);
            Files.write(tempFilePath, content);

            tempFilePath.toFile().setExecutable(true);
            return tempFilePath.toString();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private File getSurefireReportsDirectory( MavenProject subProject ) {
        String buildDir = subProject.getBuild().getDirectory();
        return new File( buildDir + "/surefire-reports" );
    }

    private List<URL> getNopolClasspath() {
        List<URL> classpath = new ArrayList<>();
        Artifact artifactPom = artifactFactory.createArtifact("fr.inria.gforge.spirals","nopol", HARDCODED_NOPOL_VERSION, null, "pom");
        Artifact artifactJar = artifactFactory.createArtifact("fr.inria.gforge.spirals","nopol", HARDCODED_NOPOL_VERSION, null, "jar");
        File filePom = new File(localRepository.getBasedir() + "/" + localRepository.pathOf(artifactPom));
        File fileJar = new File(localRepository.getBasedir() + "/" + localRepository.pathOf(artifactJar));

        if (filePom.exists()) {
            MavenXpp3Reader pomReader = new MavenXpp3Reader();
            try (FileReader reader = new FileReader(filePom)) {
                Model model = pomReader.read(reader);

                List<Dependency> dependencies = model.getDependencies();
                for (Dependency dependency : dependencies) {
                    if (!dependency.isOptional() && dependency.getScope() == null && dependency.getVersion() != null) {
                        Artifact artifact = artifactFactory.createArtifact(dependency.getGroupId(),dependency.getArtifactId(), dependency.getVersion(), null, dependency.getType());
                        File jarFile = new File(localRepository.getBasedir() + "/" + localRepository.pathOf(artifact));

                        classpath.add(jarFile.toURI().toURL());
                    } else if ("system".equals(dependency.getScope())) {
                        String path = dependency.getSystemPath().replace("${java.home}", System.getProperty("java.home"));
                        File jarFile = new File(path);
                        if (jarFile.exists()) {
                            classpath.add(jarFile.toURI().toURL());
                        }
                    }
                }
                classpath.add(fileJar.toURI().toURL());
            } catch (Exception e) {
                e.printStackTrace();
                System.err.println("Error occured, dependency will be passed: "+e.getMessage());
            }
        }
        return classpath;
    }

    @Override
    public List<URL> getClasspath() {
        List<URL> classpath = super.getClasspath();

        Artifact artifactJar = artifactFactory.createArtifact("fr.inria.gforge.spirals","nopol", HARDCODED_NOPOL_VERSION, null, "jar");
        File fileJar = new File(localRepository.getBasedir() + "/" + localRepository.pathOf(artifactJar));

        try {
            if (fileJar.exists()) {
                classpath.add(fileJar.toURI().toURL());
            }
            String path = System.getProperty("java.home") + "/../lib/tools.jar";
            File jarFile = new File(path);
            if (jarFile.exists()) {
                classpath.add(jarFile.toURI().toURL());
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Error occured, dependency will be passed: "+e.getMessage());
        }
        return new ArrayList<>(classpath);
    }

	public NopolResult getResult() {
		return result;
	}
}
