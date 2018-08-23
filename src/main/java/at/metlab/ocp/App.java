package at.metlab.ocp;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;

import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildConfigList;
import io.fabric8.openshift.api.model.DeploymentCondition;
import io.fabric8.openshift.api.model.DeploymentConfig;
import io.fabric8.openshift.api.model.DeploymentConfigList;
import io.fabric8.openshift.api.model.Project;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.openshift.client.OpenShiftConfig;
import io.fabric8.openshift.client.OpenShiftConfigBuilder;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.spring.autoconfigure.MeterRegistryCustomizer;

@SpringBootApplication
@Controller
@Configuration
public class App {

	private static Log LOGGER = LogFactory.getLog(App.class);

	@Autowired
	private MeterRegistry meterRegistry;

	@Autowired
	private OpenShiftClient openShiftClient;

	private final Map<String, Long> lastDeploymentFailed = new ConcurrentHashMap<>();

	private final Map<String, Long> lastBuildFailed = new ConcurrentHashMap<>();

	public static void main(String[] args) {
		SpringApplication.run(App.class, args);
	}

	@Scheduled(fixedDelay = 1 * 60 * 1000)
	public void updateMetrics() {
		LOGGER.info("scanning projects");

		for (Project project : openShiftClient.projects().list().getItems()) {
			String namespace = project.getMetadata().getName();

			scanDeploymentConfigs(namespace);
			scanBuildConfigs(namespace);
		}

		LOGGER.info("projects scanned");
	}

	private void scanBuildConfigs(String namespace) {
		// scan build configs
		BuildConfigList bcl = openShiftClient.buildConfigs()//
				.inNamespace(namespace)//
				.list();

		nextBuildConfig: for (BuildConfig bc : bcl.getItems()) {
			String bcName = bc.getMetadata().getName();

			if (bc.getStatus().getLastVersion() != null) {
				String resourceVersion = String.valueOf(bc.getStatus().getLastVersion());

				Build build = openShiftClient//
						.builds()//
						.inNamespace(namespace)//
						.withName(bcName + "-" + resourceVersion)//
						.get();

				if (build != null) {
					setLastBuildFailed(namespace, bcName, hasFailed(build));
					continue nextBuildConfig;
				}
			}

			setLastBuildFailed(namespace, bcName, false);
		}
	}

	private void scanDeploymentConfigs(String namespace) {
		// scan deployment configs
		DeploymentConfigList dcl = openShiftClient.deploymentConfigs()//
				.inNamespace(namespace)//
				.list();

		nextDeployment: for (DeploymentConfig dc : dcl.getItems()) {
			String dcName = dc.getMetadata().getName();

			for (DeploymentCondition deploymentCondition : dc.getStatus().getConditions()) {
				if (hasFailed(deploymentCondition)) {
					setLastDeploymentFailed(namespace, dcName, true);
					continue nextDeployment;
				}
			}

			setLastDeploymentFailed(namespace, dcName, false);
		}
	}

	private void setLastDeploymentFailed(String namespace, String dcName, boolean failed) {
		String mapKey = namespace + ":" + dcName;
		Long lFailed = failed ? 1L : 0L;
		lastDeploymentFailed.put(mapKey, lFailed);

		Tags tags = Tags.of("namespace", namespace).and("dc", dcName);
		Gauge gauge = meterRegistry//
				.find("ocp_dc_last_deployment_failed")//
				.tags(tags)//
				.gauge();

		if (gauge == null) {
			meterRegistry.gauge("ocp_dc_last_deployment_failed", //
					tags, //
					lastDeploymentFailed, //
					new ToDoubleFunction<Map<String, Long>>() {
						@Override
						public double applyAsDouble(Map<String, Long> value) {
							return value.get(mapKey);
						}
					});

			LOGGER.info("registered gauge for dc/" + dcName + " in " + namespace);
		}
	}

	private static boolean hasFailed(DeploymentCondition deploymentCondition) {
		// DeploymentCondition(lastTransitionTime=2018-08-23T20:31:22Z,
		// lastUpdateTime=2018-08-23T20:31:22Z, message=replication controller
		// "ruby-ex-3" has failed progressing, reason=ProgressDeadlineExceeded,
		// status=False, type=Progressing, additionalProperties={})

		return "ProgressDeadlineExceeded".equals(deploymentCondition.getReason()) && //
				"False".equals(deploymentCondition.getStatus()) && //
				"Progressing".equals(deploymentCondition.getType());
	}

	private void setLastBuildFailed(String namespace, String bcName, boolean failed) {
		String mapKey = namespace + ":" + bcName;
		Long lFailed = failed ? 1L : 0L;
		lastBuildFailed.put(mapKey, lFailed);

		Tags tags = Tags.of("namespace", namespace).and("bc", bcName);
		Gauge gauge = meterRegistry//
				.find("ocp_bc_last_build_failed")//
				.tags(tags)//
				.gauge();

		if (gauge == null) {
			meterRegistry.gauge("ocp_bc_last_build_failed", //
					tags, //
					lastBuildFailed, //
					new ToDoubleFunction<Map<String, Long>>() {
						@Override
						public double applyAsDouble(Map<String, Long> value) {
							return value.get(mapKey);
						}
					});

			LOGGER.info("registered gauge for bc/" + bcName + " in " + namespace);
		}
	}

	private static boolean hasFailed(Build build) {
		return "Failed".equals(build.getStatus().getPhase());
	}

	@Bean
	public OpenShiftClient openShiftClient() {
		OpenShiftConfig config = new OpenShiftConfigBuilder()//
				.build();

		return new DefaultOpenShiftClient(config);
	}

	@Bean
	public MeterRegistryCustomizer<?> meterRegistryCustomizer(MeterRegistry meterRegistry) {
		return meterRegistry1 -> {
			meterRegistry.config().commonTags("application", "ocp-project-metrics-reporter");
		};
	}

}
