package dev.pomeroy.dataflow.controlplane;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions;

/**
 * The application's single Spring Data JDBC conversions registry. Spring allows one
 * {@link JdbcCustomConversions} per context, but jsonb document columns belong to
 * several modules — so, like the other root-package composition classes, this one
 * assembles what the modules contribute: every {@link Converter} bean in the context
 * is, by convention, a jsonb document converter and is collected here.
 */
@Configuration(proxyBeanMethods = false)
class JsonbConversions {

	@Bean
	JdbcCustomConversions jdbcCustomConversions(List<Converter<?, ?>> jsonbConverters) {
		return new JdbcCustomConversions(jsonbConverters);
	}
}
