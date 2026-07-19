package dev.pomeroy.dataflow.controlplane;

import java.util.List;
import org.postgresql.util.PGobject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions;

/**
 * The application's single Spring Data JDBC conversions registry. Spring allows one
 * {@link JdbcCustomConversions} per context, but jsonb document columns belong to
 * several modules — so, like the other root-package composition classes, this one
 * assembles what the modules contribute: every {@link Converter} bean in the context
 * is, by convention, a jsonb document converter. The convention is enforced, not
 * assumed: a converter bean that neither reads nor writes {@link PGobject} fails
 * startup here with its name, instead of silently joining the JDBC conversions.
 * (A marker interface would say this more directly, but modules may not depend on
 * the application root package — the Modulith boundary test forbids the cycle.)
 */
@Configuration(proxyBeanMethods = false)
class JsonbConversions {

	@Bean
	JdbcCustomConversions jdbcCustomConversions(List<Converter<?, ?>> jsonbConverters) {
		jsonbConverters.forEach(JsonbConversions::requireJsonbShape);
		return new JdbcCustomConversions(jsonbConverters);
	}

	private static void requireJsonbShape(Converter<?, ?> converter) {
		ResolvableType[] generics = ResolvableType.forClass(converter.getClass())
				.as(Converter.class).getGenerics();
		for (ResolvableType generic : generics) {
			if (generic.resolve() == PGobject.class) {
				return;
			}
		}
		throw new IllegalStateException(
				"%s is a Converter bean but not a jsonb document converter (neither side is PGobject) — every Converter bean joins the single JdbcCustomConversions registry, so give it another shape"
						.formatted(converter.getClass().getName()));
	}
}
