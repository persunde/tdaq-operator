package ch.cern.tdaq.k8s.operator;

import com.github.containersolutions.operator.Operator;
import com.github.containersolutions.operator.processing.retry.GenericRetry;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.takes.facets.fork.FkRegex;
import org.takes.facets.fork.TkFork;
import org.takes.http.Exit;
import org.takes.http.FtBasic;

import java.io.IOException;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws IOException {
        /**
         * TODO: No loop per X second. Need to implement this! Ask the java-sdk guys in Discord for release info
         * Need this for deleting finished deployments!
         */
        log.info("Runner TDAQ Operator starting!");

        Config config = new ConfigBuilder().withNamespace(null).build();
        KubernetesClient client = new DefaultKubernetesClient(config);
        Operator operator = new Operator(client);
        GenericRetry retry = GenericRetry.every10second10TimesRetry(); /* On Failure: retries every 10 second, and max 10 times, you can customize this yourself if you want */

        RunController controller = new RunController(client);
        operator.registerControllerForAllNamespaces(controller, retry);

        /**
         * New thread that loops and checks if there are any deployments with 0 Pods. If so, then it deletes them.
         * NOTE: This should be replaced and removed when looping based on time interval is supported by java-operator-sdk ... Coming Soon TM...?
         *  See: https://github.com/ContainerSolutions/java-operator-sdk/issues/157
         */
        Runnable runnable = () -> {
            Config config2 = new ConfigBuilder().withNamespace(null).build();
            KubernetesClient kubernetesClient = new DefaultKubernetesClient(config2);
            while (true) {
                try {
                    Thread.sleep(30 * 1000);
                    RunController.deleteFinishedDeployments(kubernetesClient);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } catch (RuntimeException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        };
        Thread t = new Thread(runnable);
        t.start();

        /**
         * The health check status endpoint. Needs to return 200 OK to signal K8S that it is OK (or not)
         */
        new FtBasic(
                new TkFork(new FkRegex("/health", "ALL GOOD!")), 8080
        ).start(Exit.NEVER);
    }
}
