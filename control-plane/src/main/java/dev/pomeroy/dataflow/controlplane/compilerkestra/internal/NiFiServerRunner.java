package dev.pomeroy.dataflow.controlplane.compilerkestra.internal;

import dev.pomeroy.dataflow.controlplane.compiler.ExecutionPlan;
import dev.pomeroy.dataflow.controlplane.compilernifi.NiFiArtifact;
import dev.pomeroy.dataflow.controlplane.compilernifi.NiFiFlowCompiler;
import org.springframework.stereotype.Component;

/**
 * The {@code nifi × server} Runner's run-time half (M4.4): a generated curl+jq shell
 * script — embedded via {@code inputFiles}, run in a pinned image as an ephemeral
 * sibling container — drives one Run of the deployed process group over NiFi's REST
 * API. Runs never touch the control plane (the M1/M2 posture): the deploy-time half
 * (upload, parameter context, controller services) lives in the {@code runner}
 * module, and everything the driver needs at run time rides in the flow itself.
 *
 * <p>The protocol is spike #38's, with spike #39's hard-won corrections: token →
 * clean start (stop, force-DISABLE seeds, drop all queues) → late-bind this run's
 * parameters through the async update-request API → enable controller services (they
 * arrive DISABLED and the credentials service refuses to enable until the sensitive
 * parameters hold real values, so this must follow the update) → start the group
 * (DISABLED seeds are skipped) → enable + RUN_ONCE each seed (one run's worth of
 * trigger, explicit —
 * a timer seed inside a start/stop window is a race, reproduced live) → poll drained
 * (0 queued for 3 consecutive polls, only after work was seen — RUN_ONCE on an
 * invalid processor is a silent no-op) / failed (failure-funnel connection depth) /
 * timeout → stop → exit with the run's status.
 *
 * <p>NiFi re-mints every live component id on upload but preserves compiler-minted
 * identifiers as {@code versionedComponentId} (spike #38), so the driver carries the
 * artifact's deterministic seed and failure-connection ids and resolves them to live
 * ids once at run start. Sensitive values (ADR-0002) come from Kestra secrets and
 * transit exactly one TLS REST call per run — the parameter update-request.
 *
 * <p>{@code BUSINESS_DATE} resolution and the sibling-container plumbing follow
 * {@link HopBatchRunner} (issue #25 / spec #19); each engine matrix cell owns its
 * task shape in a class like this one.
 */
@Component
public class NiFiServerRunner {

	/** The pinned driver image (docs/versions.md) — bump only via that registry. */
	static final String DRIVER_IMAGE = "badouralix/curl-jq:alpine@sha256:e1f1e84c4c23c24d665cd9243dcf7fa531965a0b37b89a64cabca847d834dd62";

	/**
	 * The generated driver, POSIX sh for the pinned image's busybox shell. A template
	 * with {@code __TOKENS__} spliced by {@link #driver}, not {@code formatted} — the
	 * script's own {@code %}-free text is not worth escaping around. The parameter
	 * update hardcodes the four contract names; the golden tests assert it covers
	 * every {@link NiFiArtifact#PARAMETERS} entry, so the contract cannot drift
	 * silently. The script must never contain two adjacent left braces:
	 * {@code inputFiles} content passes through Kestra's Pebble renderer, which
	 * would treat them as an expression open.
	 */
	private static final String DRIVER_TEMPLATE = """
			#!/bin/sh
			# Generated NiFi run driver (nifi x server): one Run of the deployed process
			# group, driven entirely over NiFi's REST API with the control plane stopped.
			set -eu

			NIFI=https://nifi:8443/nifi-api
			SLUG=__SLUG__
			SEED_IDS='__SEED_IDS__'
			FAILURE_CONNECTION_IDS='__FAILURE_CONNECTION_IDS__'
			POLL_INTERVAL=2
			POLL_BUDGET=90
			DRAINED_POLLS=3

			fail() { echo "FAIL: $*" >&2; exit 1; }

			# authenticated JSON call; -k accepts the self-signed cert, the M0 posture
			api() {
			  method=$1; path=$2; shift 2
			  curl -sSk --fail-with-body -X "$method" "$NIFI$path" \\
			    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' "$@"
			}

			run_status() {
			  rev=$(api GET "/processors/$1" | jq -c .revision)
			  api PUT "/processors/$1/run-status" \\
			    -d "$(jq -n --argjson rev "$rev" --arg state "$2" '{revision: $rev, state: $state}')" \\
			    >/dev/null
			}

			pg_state() {
			  api PUT "/flow/process-groups/$PG" \\
			    -d "$(jq -n --arg id "$PG" --arg state "$1" '{id: $id, state: $state}')" >/dev/null
			}

			echo "run $RUN_ID of $SLUG, business date $BUSINESS_DATE"
			TOKEN=$(curl -sSk --fail-with-body -X POST "$NIFI/access/token" \\
			  --data-urlencode "username=$NIFI_USERNAME" \\
			  --data-urlencode "password=$NIFI_PASSWORD") || fail "cannot authenticate against NiFi"

			PG=$(api GET /flow/process-groups/root | jq -r --arg name "$SLUG" \\
			  '.processGroupFlow.flow.processGroups[] | select(.component.name == $name) | .id')
			[ -n "$PG" ] || fail "no process group named $SLUG"

			# NiFi re-mints live ids on upload; the artifact's deterministic ids survive
			# as versionedComponentId — resolve them once (spike #38)
			PROCESSORS=$(api GET "/process-groups/$PG/processors")
			CONNECTIONS=$(api GET "/process-groups/$PG/connections")
			SEED_LIVE=''
			for minted in $SEED_IDS; do
			  live=$(echo "$PROCESSORS" | jq -r --arg v "$minted" \\
			    '.processors[] | select(.component.versionedComponentId == $v) | .id')
			  [ -n "$live" ] || fail "seed $minted not found in the live process group"
			  SEED_LIVE="$SEED_LIVE$live "
			done
			FAILURE_LIVE=''
			for minted in $FAILURE_CONNECTION_IDS; do
			  live=$(echo "$CONNECTIONS" | jq -r --arg v "$minted" \\
			    '.connections[] | select(.component.versionedComponentId == $v) | .id')
			  [ -n "$live" ] || fail "failure connection $minted not found in the live process group"
			  FAILURE_LIVE="$FAILURE_LIVE$live "
			done

			# clean start regardless of any previous run's corpse: stop, force-DISABLE the
			# seeds (an enabled seed timer-fires the instant the group starts), drop queues
			pg_state STOPPED
			for seed in $SEED_LIVE; do run_status "$seed" DISABLED; done
			DROP=$(api POST "/process-groups/$PG/empty-all-connections-requests" | jq -r .dropRequest.id)
			tries=0
			until [ "$(api GET "/process-groups/$PG/empty-all-connections-requests/$DROP" | jq -r .dropRequest.finished)" = true ]; do
			  tries=$((tries + 1))
			  [ "$tries" -le 30 ] || fail "queue drop did not finish"
			  sleep 1
			done
			api DELETE "/process-groups/$PG/empty-all-connections-requests/$DROP" >/dev/null

			# late-bind this run's parameters; sensitive values transit only this call
			CTX=$(api GET /flow/parameter-contexts | jq -r --arg name "$SLUG" \\
			  '.parameterContexts[] | select(.component.name == $name) | .id')
			[ -n "$CTX" ] || fail "no parameter context named $SLUG"
			UPDATE=$(jq -n --arg id "$CTX" \\
			  --argjson rev "$(api GET "/parameter-contexts/$CTX" | jq .revision.version)" \\
			  --arg businessDate "$BUSINESS_DATE" --arg runId "$RUN_ID" \\
			  --arg minioAccessKey "$MINIO_ACCESS_KEY" --arg minioSecretKey "$MINIO_SECRET_KEY" \\
			  '{revision: {version: $rev}, id: $id, component: {id: $id, parameters: [
			    {parameter: {name: "businessDate", sensitive: false, value: $businessDate}},
			    {parameter: {name: "runId", sensitive: false, value: $runId}},
			    {parameter: {name: "minioAccessKey", sensitive: true, value: $minioAccessKey}},
			    {parameter: {name: "minioSecretKey", sensitive: true, value: $minioSecretKey}}
			  ]}}')
			REQ=$(api POST "/parameter-contexts/$CTX/update-requests" -d "$UPDATE" | jq -r .request.requestId)
			tries=0
			until [ "$(api GET "/parameter-contexts/$CTX/update-requests/$REQ" | jq -r .request.complete)" = true ]; do
			  tries=$((tries + 1))
			  [ "$tries" -le 60 ] || fail "parameter update did not complete"
			  sleep 1
			done
			REASON=$(api GET "/parameter-contexts/$CTX/update-requests/$REQ" | jq -r .request.failureReason)
			api DELETE "/parameter-contexts/$CTX/update-requests/$REQ" >/dev/null
			[ "$REASON" = null ] || fail "parameter update failed: $REASON"

			# enable controller services: they arrive DISABLED on upload (spike #38) and
			# deploy leaves them that way, because the credentials service refuses to
			# enable until the sensitive parameters hold real values - which they now do
			api PUT "/flow/process-groups/$PG/controller-services" \\
			  -d "$(jq -n --arg id "$PG" '{id: $id, state: "ENABLED"}')" >/dev/null
			tries=0
			until [ "$(api GET "/flow/process-groups/$PG/controller-services" \\
			  | jq '[.controllerServices[] | select(.component.state != "ENABLED")] | length')" = 0 ]; do
			  tries=$((tries + 1))
			  [ "$tries" -le 30 ] || fail "controller services did not all enable"
			  sleep 1
			done

			# start (DISABLED seeds are skipped), then one run's worth of trigger, explicit.
			# Freshly-uploaded processors arrive DISABLED and unvalidated - enabling is
			# what kicks validation off, and RUN_ONCE on a not-yet-VALID processor is a
			# silent no-op (spike #39) - so wait for VALID between the two.
			pg_state RUNNING
			for seed in $SEED_LIVE; do
			  run_status "$seed" STOPPED
			  tries=0
			  until [ "$(api GET "/processors/$seed" | jq -r .component.validationStatus)" = VALID ]; do
			    tries=$((tries + 1))
			    if [ "$tries" -gt 30 ]; then
			      pg_state STOPPED
			      fail "seed $seed never validated"
			    fi
			    sleep 1
			  done
			  run_status "$seed" RUN_ONCE
			done

			# drained = 0 queued for DRAINED_POLLS consecutive polls, counted only after
			# work was seen: RUN_ONCE on an invalid processor is a silent no-op (spike #39),
			# and an untouched group polls 0 from the first sample
			STATUS=TIMEOUT
			SAW_WORK=0
			ZEROES=0
			polls=0
			while [ "$polls" -lt "$POLL_BUDGET" ]; do
			  polls=$((polls + 1))
			  for conn in $FAILURE_LIVE; do
			    depth=$(api GET "/flow/connections/$conn/status" | jq -r .connectionStatus.aggregateSnapshot.flowFilesQueued)
			    if [ "$depth" -gt 0 ]; then
			      echo "failure funnel connection $conn holds $depth flowfile(s)" >&2
			      STATUS=FAILED
			    fi
			  done
			  if [ "$STATUS" = FAILED ]; then break; fi
			  QUEUED=$(api GET "/flow/process-groups/$PG/status?recursive=true" | jq -r .processGroupStatus.aggregateSnapshot.flowFilesQueued)
			  if [ "$QUEUED" -gt 0 ]; then
			    SAW_WORK=1
			    ZEROES=0
			  elif [ "$SAW_WORK" -eq 1 ]; then
			    ZEROES=$((ZEROES + 1))
			    if [ "$ZEROES" -ge "$DRAINED_POLLS" ]; then
			      STATUS=DRAINED
			      break
			    fi
			  fi
			  sleep "$POLL_INTERVAL"
			done

			# stop; seeds back to DISABLED so the next start cannot timer-fire them
			pg_state STOPPED
			for seed in $SEED_LIVE; do run_status "$seed" DISABLED; done

			case "$STATUS" in
			  DRAINED) echo "run $RUN_ID drained clean" ;;
			  FAILED) fail "run $RUN_ID failed - flowfiles in the failure funnel, nothing staged" ;;
			  *) fail "run $RUN_ID did not drain within $((POLL_BUDGET * POLL_INTERVAL))s (saw work: $SAW_WORK)" ;;
			esac
			""";

	private final NiFiFlowCompiler nifiCompiler;

	public NiFiServerRunner(NiFiFlowCompiler nifiCompiler) {
		this.nifiCompiler = nifiCompiler;
	}

	/** The engine-runner task, YAML lines ready to splice into the flow's task list. */
	String task(ExecutionPlan plan) {
		NiFiArtifact artifact = nifiCompiler.compile(plan);
		return """
				  - id: engine_runner
				    type: io.kestra.plugin.scripts.shell.Commands
				    taskRunner:
				      type: io.kestra.plugin.scripts.runner.docker.Docker
				      networkMode: %s
				      pullPolicy: IF_NOT_PRESENT
				    containerImage: %s
				    env:
				      RUN_ID: "{{ execution.id }}"
				      BUSINESS_DATE: "{{ inputs.businessDate ?? (execution.startDate | date('yyyy-MM-dd', timeZone='%s')) }}"
				      NIFI_USERNAME: "{{ secret('NIFI_USERNAME') }}"
				      NIFI_PASSWORD: "{{ secret('NIFI_PASSWORD') }}"
				      MINIO_ACCESS_KEY: "{{ secret('MINIO_ACCESS_KEY') }}"
				      MINIO_SECRET_KEY: "{{ secret('MINIO_SECRET_KEY') }}"
				    inputFiles:
				""".formatted(HopBatchRunner.COMPOSE_NETWORK, DRIVER_IMAGE,
				businessDateTimezone(plan))
				+ inputFile("driver.sh", driver(plan, artifact)) + """
				    commands:
				      - sh driver.sh
				""";
	}

	private String driver(ExecutionPlan plan, NiFiArtifact artifact) {
		return DRIVER_TEMPLATE.replace("__SLUG__", plan.slug())
				.replace("__SEED_IDS__", String.join(" ", artifact.seedProcessorIds()))
				.replace("__FAILURE_CONNECTION_IDS__",
						String.join(" ", artifact.failureConnectionIds()));
	}

	/** The timezone the run-date default resolves Business Date in (#25). */
	private String businessDateTimezone(ExecutionPlan plan) {
		return plan.schedule() != null ? plan.schedule().timezone() : "UTC";
	}

	/**
	 * One embedded input file as a literal block scalar — content lines indented
	 * under the key, empty lines left empty so no line ever carries trailing spaces.
	 */
	private String inputFile(String name, String content) {
		StringBuilder yaml = new StringBuilder("      ").append(name).append(": |\n");
		content.lines().forEach(
				line -> yaml.append(line.isEmpty() ? "" : "        " + line).append('\n'));
		return yaml.toString();
	}
}
