package integration_tests;

import hudson.EnvVars;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.http.client.HttpClient;
import org.junit.Before;
import org.junit.Test;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Paths;
import java.util.Map;

import com.meterian.common.system.OS;
import com.meterian.common.system.Shell;
import io.meterian.jenkins.core.Meterian;
import io.meterian.jenkins.glue.MeterianPlugin;
import io.meterian.jenkins.io.ClientDownloader;
import io.meterian.jenkins.io.HttpClientFactory;

import static junit.framework.TestCase.fail;
import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.assertThat;

public class MeterianClientTest {

    private static final String BASE_URL = "https://www.meterian.com";
    private static final String CURRENT_WORKING_DIR = System.getProperty("user.dir");

    private static final String NO_JVM_ARGS = "";

    private String gitRepoWorkingFolder;
    private EnvVars environment;

    @Before
    public void setup() throws IOException {
        String gitRepoRootFolder = Paths.get(CURRENT_WORKING_DIR, "target/github-repo/").toString();
        FileUtils.deleteDirectory(new File(gitRepoRootFolder));

        new File(gitRepoRootFolder).mkdir();

        gitRepoWorkingFolder = performCloneGitRepo("MeterianHQ", "autofix-sample-maven-upgrade", gitRepoRootFolder);
    }

    @Test
    public void givenConfiguration_whenMeterianClientIsRun_thenItShouldNotThrowException() throws IOException {
        File logFile = File.createTempFile("jenkins-logger", Long.toString(System.nanoTime()));
        PrintStream jenkinsLogger = new PrintStream(logFile);

        // Given: we are setup to run the meterian client against a repo that has vulnerabilities
        environment = getEnvironment();
        String meterianAPIToken = environment.get("METERIAN_API_TOKEN");
        assertThat("METERIAN_API_TOKEN has not been set, cannot run test without a valid value", meterianAPIToken, notNullValue());
        String meterianGithubToken = environment.get("METERIAN_GITHUB_TOKEN");
        assertThat("METERIAN_GITHUB_TOKEN has not been set, cannot run test without a valid value", meterianGithubToken, notNullValue());

        String meterianGithubUser = getMeterianGithubUser();
        if ((meterianGithubUser == null) || meterianGithubUser.trim().isEmpty()) {
            jenkinsLogger.println("METERIAN_GITHUB_USER has not been set, tests will be run using the default value assumed for this environment variable");
        }

        String meterianGithubEmail = getMeterianGithubEmail();
        if ((meterianGithubEmail == null) || meterianGithubEmail.trim().isEmpty()) {
            jenkinsLogger.println("METERIAN_GITHUB_EMAIL has not been set, tests will be run using the default value assumed for this environment variable");
        }

        MeterianPlugin.Configuration configuration = new MeterianPlugin.Configuration(
                BASE_URL,
                meterianAPIToken,
                NO_JVM_ARGS,
                meterianGithubUser,
                meterianGithubEmail,
                meterianGithubToken
        );

        String args = "";

        // When: the meterian client is run against the locally cloned git repo
        try {
            File clientJar = new ClientDownloader(newHttpClient(), BASE_URL, nullPrintStream()).load();
            Meterian client = Meterian.build(configuration, environment, jenkinsLogger, args, clientJar);
            client.prepare("--interactive=false");
            client.run();
            jenkinsLogger.close();
        } catch (Exception ex) {
            fail("Should not have failed with the exception: " + ex.getMessage());
        }

        // Then: we should be able to see the expecting output in the execution analysis output logs
        String runAnalysisLogs = readRunAnalysisLogs(logFile.getPath());
        assertThat(runAnalysisLogs, containsString("[meterian] Client successfully authorized"));
        assertThat(runAnalysisLogs, containsString("[meterian] Meterian Client v"));
        assertThat(runAnalysisLogs, containsString("[meterian] Project information:"));
        assertThat(runAnalysisLogs, containsString("[meterian] JAVA scan -"));
        assertThat(runAnalysisLogs, containsString("MeterianHQ/autofix-sample-maven-upgrade.git"));
        assertThat(runAnalysisLogs, containsString("[meterian] Full report available at: "));
        assertThat(runAnalysisLogs, containsString("[meterian] Build unsuccesful!"));
        assertThat(runAnalysisLogs, containsString("[meterian] Failed checks: [security]"));
    }

    private String performCloneGitRepo(String githubOrgOrUserName, String githubProjectName, String gitRepoRootFolder) throws IOException {
        String[] gitCloneRepoCommand = new String[] {
                "git",
                "clone",
                String.format("git@github.com:%s/%s.git", githubOrgOrUserName, githubProjectName) // only use ssh or git protocol and not https - uses ssh keys to authenticate
        };

        Shell.Options options = new Shell.Options().
                onDirectory(new File(gitRepoRootFolder));
        Shell.Task task = new Shell().exec(
                gitCloneRepoCommand,
                options
        );
        task.waitFor();

        assertThat("Cannot run the test, as we were unable to clone the target git repo due to error code: " +
                task.exitValue(), task.exitValue(), is(equalTo(0)));

        return Paths.get(gitRepoRootFolder, githubProjectName).toString();
    }

    private String readRunAnalysisLogs(String pathToLog) throws IOException {
        File logFile = new File(pathToLog);
        return FileUtils.readFileToString(logFile);
    }

    private PrintStream nullPrintStream() {
        return new PrintStream(new NullOutputStream());
    }

    private static HttpClient newHttpClient() {
        return new HttpClientFactory().newHttpClient(new HttpClientFactory.Config() {
            @Override
            public int getHttpConnectTimeout() {
                return Integer.MAX_VALUE;
            }

            @Override
            public int getHttpSocketTimeout() {
                return Integer.MAX_VALUE;
            }

            @Override
            public int getHttpMaxTotalConnections() {
                return 100;
            }

            @Override
            public int getHttpMaxDefaultConnectionsPerRoute() {
                return 100;
            }

            @Override
            public String getHttpUserAgent() {
                // TODO Auto-generated method stub
                return null;
            }});
    }

    private EnvVars getEnvironment() {
        EnvironmentVariablesNodeProperty prop = new EnvironmentVariablesNodeProperty();
        EnvVars environment = prop.getEnvVars();

        Map<String, String> localEnvironment = new OS().getenv();
        for (String envKey: localEnvironment.keySet()) {
            environment.put(envKey, localEnvironment.get(envKey));
        }
        environment.put("WORKSPACE", gitRepoWorkingFolder);
        return environment;
    }

    private String getMeterianGithubUser() {
        return environment.get("METERIAN_GITHUB_USER", "meterian-bot");
    }

    private String getMeterianGithubEmail() {
        return environment.get("METERIAN_GITHUB_EMAIL", "bot.github@meterian.io");
    }
}