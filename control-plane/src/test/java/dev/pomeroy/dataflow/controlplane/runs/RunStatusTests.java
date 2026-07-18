package dev.pomeroy.dataflow.controlplane.runs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The closed four-state model (spec #10): Kestra's whole state zoo lands in
 * {@code QUEUED → RUNNING → SUCCEEDED | FAILED}, so dashboards reason about four
 * words — the raw state travels alongside in the Run's detail field, never here.
 */
class RunStatusTests {

	@Test
	void statesWaitingToRunAreQueued() {
		assertThat(RunStatus.of("CREATED")).isEqualTo(RunStatus.QUEUED);
		assertThat(RunStatus.of("QUEUED")).isEqualTo(RunStatus.QUEUED);
		assertThat(RunStatus.of("RESTARTED")).isEqualTo(RunStatus.QUEUED);
	}

	@Test
	void statesInFlightAreRunning() {
		assertThat(RunStatus.of("RUNNING")).isEqualTo(RunStatus.RUNNING);
		assertThat(RunStatus.of("KILLING")).isEqualTo(RunStatus.RUNNING);
		assertThat(RunStatus.of("PAUSED")).isEqualTo(RunStatus.RUNNING);
		assertThat(RunStatus.of("RETRYING")).isEqualTo(RunStatus.RUNNING);
		assertThat(RunStatus.of("BREAKPOINT")).isEqualTo(RunStatus.RUNNING);
	}

	@Test
	void terminalGoodStatesAreSucceeded() {
		assertThat(RunStatus.of("SUCCESS")).isEqualTo(RunStatus.SUCCEEDED);
		assertThat(RunStatus.of("WARNING")).isEqualTo(RunStatus.SUCCEEDED);
	}

	@Test
	void terminalBadStatesAreFailed() {
		assertThat(RunStatus.of("FAILED")).isEqualTo(RunStatus.FAILED);
		assertThat(RunStatus.of("KILLED")).isEqualTo(RunStatus.FAILED);
		assertThat(RunStatus.of("CANCELLED")).isEqualTo(RunStatus.FAILED);
		assertThat(RunStatus.of("SKIPPED")).isEqualTo(RunStatus.FAILED);
		assertThat(RunStatus.of("RETRIED")).isEqualTo(RunStatus.FAILED);
	}

	@Test
	void aStateKestraInventsTomorrowStaysNonTerminal() {
		// The model is closed but total: an unmapped state must never fabricate an
		// outcome, so it reads as in-flight and the poller keeps watching it.
		assertThat(RunStatus.of("SOME_FUTURE_STATE")).isEqualTo(RunStatus.RUNNING);
	}

	@Test
	void exactlySucceededAndFailedAreTerminal() {
		assertThat(RunStatus.QUEUED.terminal()).isFalse();
		assertThat(RunStatus.RUNNING.terminal()).isFalse();
		assertThat(RunStatus.SUCCEEDED.terminal()).isTrue();
		assertThat(RunStatus.FAILED.terminal()).isTrue();
	}
}
