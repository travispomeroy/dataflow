package dev.pomeroy.dataflow.controlplane.runs.internal;

import dev.pomeroy.dataflow.controlplane.runs.internal.DeliveredFiles.DeliveredFile;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.postgresql.util.PGobject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.relational.core.mapping.event.BeforeConvertCallback;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Run ids are server-generated, never client-supplied — the same rule the dataflow
 * spine follows. The delivered-files document maps to its {@code jsonb} column as the
 * bare {@code [{name, records}, …]} array — the stored document is exactly the API
 * document. The converters are contributed as beans for the application's single
 * conversions registry to collect.
 */
@Configuration(proxyBeanMethods = false)
class RunsPersistenceConfiguration {

	@Bean
	BeforeConvertCallback<RunEntity> serverGeneratedRunId() {
		return entity -> entity.id() != null ? entity : entity.withId(UUID.randomUUID());
	}

	@Bean
	DeliveredFilesToJsonb deliveredFilesToJsonb(ObjectMapper mapper) {
		return new DeliveredFilesToJsonb(mapper);
	}

	@Bean
	JsonbToDeliveredFiles jsonbToDeliveredFiles(ObjectMapper mapper) {
		return new JsonbToDeliveredFiles(mapper);
	}

	@WritingConverter
	static class DeliveredFilesToJsonb implements Converter<DeliveredFiles, PGobject> {

		private final ObjectMapper mapper;

		DeliveredFilesToJsonb(ObjectMapper mapper) {
			this.mapper = mapper;
		}

		@Override
		public PGobject convert(DeliveredFiles deliveredFiles) {
			PGobject jsonb = new PGobject();
			jsonb.setType("jsonb");
			try {
				jsonb.setValue(mapper.writeValueAsString(deliveredFiles.files()));
			}
			catch (SQLException e) {
				throw new IllegalStateException("Unstorable delivered-files document", e);
			}
			return jsonb;
		}
	}

	@ReadingConverter
	static class JsonbToDeliveredFiles implements Converter<PGobject, DeliveredFiles> {

		private final ObjectMapper mapper;

		JsonbToDeliveredFiles(ObjectMapper mapper) {
			this.mapper = mapper;
		}

		@Override
		public DeliveredFiles convert(PGobject jsonb) {
			List<DeliveredFile> files = mapper.readValue(jsonb.getValue(),
					new TypeReference<List<DeliveredFile>>() {
					});
			return new DeliveredFiles(files);
		}
	}
}
