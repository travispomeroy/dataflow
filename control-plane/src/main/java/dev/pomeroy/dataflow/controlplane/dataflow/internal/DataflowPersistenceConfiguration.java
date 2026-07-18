package dev.pomeroy.dataflow.controlplane.dataflow.internal;

import dev.pomeroy.dataflow.controlplane.dataflow.DataflowConfig;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.postgresql.util.PGobject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions;
import org.springframework.data.relational.core.mapping.event.BeforeConvertCallback;
import tools.jackson.databind.ObjectMapper;

/**
 * The jsonb document mapping: a {@link DataflowConfig} is one {@code jsonb} column,
 * (de)serialized by the application's shared mapper so the stored document is exactly
 * the API document. Ids are server-generated here, never client-supplied.
 */
@Configuration(proxyBeanMethods = false)
class DataflowPersistenceConfiguration {

	@Bean
	JdbcCustomConversions jdbcCustomConversions(ObjectMapper mapper) {
		return new JdbcCustomConversions(List.of(
				new DataflowConfigToJsonb(mapper), new JsonbToDataflowConfig(mapper)));
	}

	@Bean
	BeforeConvertCallback<DataflowEntity> serverGeneratedDataflowId() {
		return entity -> entity.id() != null ? entity
				: new DataflowEntity(UUID.randomUUID(), entity.slug(), entity.name(), entity.config());
	}

	@WritingConverter
	static class DataflowConfigToJsonb implements Converter<DataflowConfig, PGobject> {

		private final ObjectMapper mapper;

		DataflowConfigToJsonb(ObjectMapper mapper) {
			this.mapper = mapper;
		}

		@Override
		public PGobject convert(DataflowConfig config) {
			PGobject jsonb = new PGobject();
			jsonb.setType("jsonb");
			try {
				jsonb.setValue(mapper.writeValueAsString(config));
			}
			catch (SQLException e) {
				throw new IllegalStateException("Unstorable Dataflow Config", e);
			}
			return jsonb;
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
