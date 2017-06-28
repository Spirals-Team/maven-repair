package com.github.tdurieux.npefix.maven.plugin;

import fr.inria.spirals.npefix.config.Config;
import fr.inria.spirals.npefix.main.DecisionServer;
import fr.inria.spirals.npefix.main.all.Launcher;
import fr.inria.spirals.npefix.resi.CallChecker;
import fr.inria.spirals.npefix.resi.context.Decision;
import fr.inria.spirals.npefix.resi.context.Lapse;
import fr.inria.spirals.npefix.resi.context.NPEOutput;
import fr.inria.spirals.npefix.resi.selector.ExplorerSelector;
import fr.inria.spirals.npefix.resi.selector.GreedySelector;
import fr.inria.spirals.npefix.resi.selector.MonoExplorerSelector;
import fr.inria.spirals.npefix.resi.selector.RandomSelector;
import fr.inria.spirals.npefix.resi.selector.Selector;
import fr.inria.spirals.npefix.resi.strategies.NoStrat;
import fr.inria.spirals.npefix.resi.strategies.ReturnType;
import fr.inria.spirals.npefix.resi.strategies.Strat1A;
import fr.inria.spirals.npefix.resi.strategies.Strat1B;
import fr.inria.spirals.npefix.resi.strategies.Strat2A;
import fr.inria.spirals.npefix.resi.strategies.Strat2B;
import fr.inria.spirals.npefix.resi.strategies.Strat3;
import fr.inria.spirals.npefix.resi.strategies.Strat4;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.surefire.log.api.NullConsoleLogger;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.plugins.surefire.report.ReportTestCase;
import org.apache.maven.plugins.surefire.report.ReportTestSuite;
import org.apache.maven.plugins.surefire.report.SurefireReportParser;
import org.apache.maven.project.MavenProject;
import org.apache.maven.reporting.MavenReportException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Mojo( name = "npefix", aggregator = true,
        defaultPhase = LifecyclePhase.TEST,
        requiresDependencyResolution = ResolutionScope.TEST)
//@Execute( lifecycle = "surefire", phase = LifecyclePhase.TEST )
public class NPEFixMojo extends AbstractMojo {

    @Parameter(property = "java.version", defaultValue = "-1")
    protected String javaVersion;

    @Parameter(property = "maven.compiler.source", defaultValue = "-1")
    protected String source;

    @Parameter(property = "maven.compile.source", defaultValue = "-1")
    protected String oldSource;

    @Component
    private ArtifactFactory artifactFactory;

    /**
     * Location of the file.
     */
    @Parameter( defaultValue = "${project.build.directory}/npefix", property = "outputDir", required = true )
    private File outputDirectory;

    @Parameter( defaultValue = "${project.build.directory}/npefix", property = "resultDir", required = true )
    private File resultDirectory;

    @Parameter( defaultValue = "exploration", property = "selector", required = true )
    private String selector;

    @Parameter( defaultValue = "100", property = "laps", required = true )
    private int nbIteration;

    @Parameter(defaultValue="${project}", readonly=true, required=true)
    private MavenProject project;

    @Parameter( defaultValue = "${reactorProjects}", readonly = true )
    private List<MavenProject> reactorProjects;

    @Parameter(defaultValue="${localRepository}")
    private ArtifactRepository localRepository;


    public void execute() throws MojoExecutionException {
        List<String> npeTests = getNPETest();


        final List<String> dependencies = getClasspath();
        List<String> sourceFolders = getSourceFolders();
        List<String> testFolders = getTestFolders();

        classpath(dependencies);

        if (npeTests.isEmpty()) {
            throw new RuntimeException("No failing test with NullPointerException");
        }

        final String[] sources = new String[sourceFolders.size() + testFolders.size()];
        int indexSource = 0;

        for (int i = 0; i < testFolders.size(); i++, indexSource++) {
            String s = testFolders.get(i);
            sources[indexSource] = s;
            System.out.println("Test: " + s);
        }
        for (int i = 0; i < sourceFolders.size(); i++, indexSource++) {
            String s = sourceFolders.get(i);
            sources[indexSource] = s;
            System.out.println("Source: " + s);
        }
        File binFolder = new File(outputDirectory.getAbsolutePath() + "/npefix-bin");

        dependencies.add(binFolder.getAbsolutePath());
        if (!binFolder.exists()) {
            binFolder.mkdirs();
        }
        int complianceLevel = 7;
        if (!source.equals("-1")) {
            complianceLevel = Integer.parseInt(source.substring(2));
        } else if (!oldSource.equals("-1")) {
            complianceLevel = Integer.parseInt(oldSource.substring(2));
        } else if (!javaVersion.equals("-1")) {
            complianceLevel = Integer.parseInt(javaVersion.substring(2, 3));
        }
        System.out.println("ComplianceLevel: " + complianceLevel);

        Date initDate = new Date();

        Launcher  npefix = new Launcher(sources, outputDirectory.getAbsolutePath() + "/npefix-output", binFolder.getAbsolutePath(), classpath(dependencies), complianceLevel);

        npefix.instrument();

        NPEOutput lapses = run(npefix, npeTests);


        spoon.Launcher spoon = new spoon.Launcher();
        for (String s : sourceFolders) {
            spoon.addInputResource(s);
        }

        spoon.getModelBuilder().setSourceClasspath(dependencies.toArray(new String[0]));
        spoon.buildModel();

        JSONObject jsonObject = lapses.toJSON(spoon);
        jsonObject.put("endInit", initDate.getTime());
        try {
            for (Decision decision : CallChecker.strategySelector.getSearchSpace()) {
                jsonObject.append("searchSpace", decision.toJSON());
            }
            jsonObject.write(new FileWriter(resultDirectory.getAbsolutePath() + "/patches.json"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private NPEOutput run(Launcher  npefix, List<String> npeTests) {
        switch (selector.toLowerCase()) {
        case "dom":
            return npefix.runStrategy(npeTests,
                    new NoStrat(),
                    new Strat1A(),
                    new Strat1B(),
                    new Strat2A(),
                    new Strat2B(),
                    new Strat3(),
                    new Strat4(ReturnType.NULL),
                    new Strat4(ReturnType.VAR),
                    new Strat4(ReturnType.NEW),
                    new Strat4(ReturnType.VOID));
        case "exploration":
            return multipleRuns(npefix, npeTests, new ExplorerSelector());
        case "mono":
            Config.CONFIG.setMultiPoints(false);
            return multipleRuns(npefix, npeTests, new MonoExplorerSelector());
        case "greedy":
            return multipleRuns(npefix, npeTests, new GreedySelector());
        case "random":
            return multipleRuns(npefix, npeTests, new RandomSelector());
        }
        return null;
    }

    private NPEOutput multipleRuns(Launcher  npefix, List<String> npeTests, Selector selector) {
        DecisionServer decisionServer = new DecisionServer(selector);
        decisionServer.startServer();

        NPEOutput output = new NPEOutput();

        int countError = 0;
        while (output.size() < nbIteration) {
            if(countError > 5) {
                break;
            }
            try {
                List<Lapse> result = npefix.run(selector, npeTests);
                if(result.isEmpty()) {
                    countError++;
                    continue;
                }
                boolean isEnd = true;
                for (int i = 0; i < result.size() && isEnd; i++) {
                    Lapse lapse = result.get(i);
                    if (lapse.getOracle().getError() != null) {
                        isEnd = isEnd && lapse.getOracle().getError().contains("No more available decision");
                    } else {
                        isEnd = false;
                    }
                }
                if (isEnd) {
                    // no more decision
                    countError++;
                    continue;
                }
                countError = 0;
                if(output.size() + result.size() > nbIteration) {
                    output.addAll(result.subList(0, (nbIteration - output.size())));
                } else {
                    output.addAll(result);
                }
            } catch (OutOfMemoryError e) {
                e.printStackTrace();
                countError++;
                continue;
            } catch (Exception e) {
                if(e.getCause() instanceof OutOfMemoryError) {
                    countError++;
                    continue;
                }
                e.printStackTrace();
                countError++;
                continue;
            }
            System.out.println("Multirun " + output.size() + "/" + nbIteration + " " + ((int)(output.size()/(double)nbIteration * 100)) + "%");
        }
        output.setEnd(new Date());
        return output;
    }

    private String classpath(List<String> dependencies) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dependencies.size(); i++) {
            String s = dependencies.get(i);
            if (new File(s).exists()) {
                sb.append(s).append(File.pathSeparatorChar);
            }
        }
        final Artifact artifact =artifactFactory.createArtifact("fr.inria.spirals","npefix", "0.4-SNAPSHOT", null, "jar");
        File file = new File(
                localRepository.getBasedir() + "/" +
                        localRepository.pathOf(artifact));

        sb.append(file.getAbsoluteFile());
        System.out.println(sb);
        return sb.toString();
    }

    private List<String> getClasspath() {
        List<String> classpath = new ArrayList<String>();
        for (MavenProject mavenProject : reactorProjects) {
            try {
                classpath.addAll(mavenProject.getTestClasspathElements());
                classpath.removeAll(mavenProject.getSystemClasspathElements());
            } catch (DependencyResolutionRequiredException e) {
                continue;
            }
        }
        for (int i = 0; i < classpath.size(); i++) {
            String s = classpath.get(i);
            if (s.endsWith("test-classes")) {
                classpath.remove(s);
            }
        }

        return classpath;
    }

    private List<String> getSourceFolders() {
        Set<String> sourceFolder = new HashSet<>();
        for (MavenProject mavenProject : reactorProjects) {
            File sourceDirectory = new File(mavenProject.getBuild().getSourceDirectory());
            if (sourceDirectory.exists()) {
                sourceFolder.add(sourceDirectory.getAbsolutePath());
            }
        }
        return new ArrayList<>(sourceFolder);
    }

    private List<String> getTestFolders() {
        Set<String> sourceFolder = new HashSet<String>();
        for (MavenProject mavenProject : reactorProjects) {
            File sourceDirectory = new File(mavenProject.getBuild().getTestSourceDirectory());
            if (sourceDirectory.exists()) {
                sourceFolder.add(sourceDirectory.getAbsolutePath());
            }
        }
        return new ArrayList<>(sourceFolder);
    }

    private List<File> getReportsDirectories() {
        List<File> resolvedReportsDirectories = new ArrayList<File>();
        if ( !project.isExecutionRoot() ) {
            return null;
        }
        for (MavenProject mavenProject : reactorProjects) {
            resolvedReportsDirectories.add(getSurefireReportsDirectory(mavenProject));
        }
        return resolvedReportsDirectories;
    }

    private List<MavenProject> getProjectsWithoutRoot() {
        List<MavenProject> result = new ArrayList<MavenProject>();
        for ( MavenProject subProject : reactorProjects ) {
            if (!project.equals(subProject)) {
                result.add(subProject);
            }
        }
        return result;
    }

    private File getSurefireReportsDirectory( MavenProject subProject ) {
        String buildDir = subProject.getBuild().getDirectory();
        return new File( buildDir + "/surefire-reports" );
    }

    private List<String> getNPETest() {
        List<String> output = new ArrayList<>();

        List<File> reportsDirectories = getReportsDirectories();
        SurefireReportParser parser = new SurefireReportParser(reportsDirectories, Locale.ENGLISH, new NullConsoleLogger());
        try {
            List<ReportTestSuite> testSuites = parser.parseXMLReportFiles();
            for (int i = 0; i < testSuites.size(); i++) {
                ReportTestSuite reportTestSuite = testSuites.get(i);
                List<ReportTestCase> testCases = reportTestSuite.getTestCases();
                for (int j = 0; j < testCases.size(); j++) {
                    ReportTestCase reportTestCase = testCases.get(j);
                    if (reportTestCase.hasFailure() && reportTestCase.getFailureType().contains("NullPointerException")) {
                        output.add(reportTestCase.getFullClassName() + "#" + reportTestCase.getName());
                    }
                }
            }
        } catch (MavenReportException e) {
            e.printStackTrace();
        }
        return output;
    }
}
