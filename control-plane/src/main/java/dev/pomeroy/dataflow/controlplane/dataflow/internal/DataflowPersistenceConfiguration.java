package dev.pomeroy.dataflow.controlplane.dataflow.internal;

import dev.pomeroy.dataflow.controlplane.dataflow.DataflowConfig;
import java.sql.SQLException;
import java.util.UUID;
import org.postgresql.util.PGobject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.relational.core.mapping.event.BeforeConvertCallback;
import tools.jackson.databind.ObjectMapper;

/**
 * The jsonb document mapping: a {@link DataflowConfig} is one {@code jsonb} column,
 * (de)serialized by the application's shared mapper so the stored document is exactly
 * the API document. The converters are contributed as beans for the application's
 * single conversions registry to collect. Ids are server-generated here, never
 * client-supplied.
 */
@Configuration(proxyBeanMethods = false)
class DataflowPersistenceConfiguration {

	@Bean
	DataflowConfigToJsonb dataflowConfigToJsonb(ObjectMapper mapper) {
		return new DataflowConfigToJsonb(mapper);
	}

	@Bean
	JsonbToDataflowConfig jsonbToDataflowConfig(ObjectMapper mapper) {
		return new JsonbToDataflowConfig(mapper);
	}

	@Bean
	PlanSnapshotToJsonb planSnapshotToJsonb() {
		return new PlanSnapshotToJsonb();
	}

	@Bean
	JsonbToPlanSnapshot jsonbToPlanSnapshot() {
		return new JsonbToPlanSnapshot();
	}

	@Bean
	BeforeConvertCallback<DataflowEntity> serverGeneratedDataflowId() {
		return entity -> entity.id() != null ? entity
				: new DataflowEntity(UUID.randomUUID(), entity.slug(), entity.name(), entity.config());
	}

	@Bean
	BeforeConvertCallback<DeploymentEntity> serverGeneratedDeploymentId() {
		return entity -> entity.id() != null ? entity
				: new DeploymentEntity(UUID.randomUUID(), entity.dataflowId(), entity.version(),
						entity.config(), entity.plan(), entity.deployedAt(), entity.active());
	}

	private static PGobject jsonb(String document, String whatItIs) {
		PGobject jsonb = new PGobject();
		jsonb.setType("jsonb");
		try {
			jsonb.setValue(document);
		}
		catch (SQLException e) {
			throw new IllegalStateException("Unstorable " + whatItIs, e);
		}
		return jsonb;
	}

	@WritingConverter
	static class DataflowConfigToJsonb implements Converter<DataflowConfig, PGobject> {

		private final ObjectMapper mapper;

		DataflowConfigToJsonb(ObjectMapper mapper) {
			this.mapper = mapper;
		}

		@Override
		public PGobject convert(DataflowConfig config) {
			return jsonb(mapper.writeValueAsString(config), "Dataflow Config");
		}
	}

	@WritingConverter
	static class PlanSnapshotToJsonb implements Converter<PlanSnapshot, PGobject> {

		@Override
		public PGobject convert(PlanSnapshot plan) {
			return jsonb(plan.json(), "Execution Plan snapshot");
		}
	}

	@ReadingConverter
	static class JsonbToPlanSnapshot implements Converter<PGobject, PlanSnapshot> {

		@Override
		public PlanSnapshot convert(PGobject jsonb) {
			return new PlanSnapshot(jsonb.getValue());
		}
	}

	@ReadingConverter
	static class JsonbToDataflowConfig implements Converter<PGobject, DataflowConfig> {

		private final ObjectMapper mapper;

		JsonbToDataflowConfig(ObjectMapper mapper) {
			this.mapper = mapper;
		}

		@Override
		public DataflowConfig convert(PGobject jsonb) {
			return mapper.readValue(jsonb.getValue(), DataflowConfig.class);
		}
	}
}
