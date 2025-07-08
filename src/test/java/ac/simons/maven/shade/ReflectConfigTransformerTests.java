/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ac.simons.maven.shade;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.plugins.shade.relocation.Relocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class ReflectConfigTransformerTests {

	private final Relocator relocator;

	ReflectConfigTransformerTests() {
		this.relocator = new Relocator() {
			@Override
			public boolean canRelocatePath(String clazz) {
				return false;
			}

			@Override
			public String relocatePath(String clazz) {
				return "";
			}

			@Override
			public boolean canRelocateClass(String clazz) {
				return Set
					.of("org.neo4j.cypherdsl.core.messages", "org.jooq.impl.SQLDataType",
							"org.jooq.util.yugabytedb.YugabyteDBDataType")
					.contains(clazz);
			}

			@Override
			public String relocateClass(String clazz) {
				return clazz + "_relocated";
			}

			@Override
			public String applyToSourceContent(String sourceContent) {
				return "";
			}
		};
	}

	static Stream<Arguments> shouldDetectTransformableResources() {
		return Stream.of(Arguments.of("blah", false), Arguments.of("META-INF/native-image/blah.json", true),
				Arguments.of("META-INF/native-images/blah.json", false),
				Arguments.of("META-INF/native-image/blah.xml", false),
				Arguments.of("META-INF/native-image/blah.json/nope.xml", false));
	}

	@ParameterizedTest
	@MethodSource
	void shouldDetectTransformableResources(String resource, boolean expected) {
		var transformer = new ReflectConfigTransformer();
		assertThat(transformer.canTransformResource(resource)).isEqualTo(expected);
	}

	@Test
	void shouldTransformReflectConfig() throws IOException {
		// language=json
		var orig = """
				[
				  {
				    "name": "org.jooq.util.yugabytedb.YugabyteDBDataType",
				    "condition": {
				      "typeReachable": "org.jooq.impl.SQLDataType"
				    }
				  },
				  {
				    "name": "a",
				    "condition": {
				      "typeReachable": "b"
				    }
				  }
				]
				""";

		var transformer = new ReflectConfigTransformer();
		try (var inputStream = new ByteArrayInputStream(orig.getBytes(StandardCharsets.UTF_8))) {
			transformer.processResource("test", inputStream, List.of(this.relocator), System.currentTimeMillis());
		}

		try (var outputStream = new ByteArrayOutputStream(); var jarOutputStream = new JarOutputStream(outputStream)) {
			transformer.modifyOutputStream(jarOutputStream);
			jarOutputStream.flush();
			jarOutputStream.finish();

			try (var jarInputStream = new JarInputStream(new ByteArrayInputStream(outputStream.toByteArray()))) {
				jarInputStream.getNextJarEntry();
				var transformed = new BufferedReader(new InputStreamReader(jarInputStream, StandardCharsets.UTF_8))
					.lines()
					.collect(Collectors.joining());
				assertThat(transformed).isEqualTo(
						"[ {  \"name\" : \"org.jooq.util.yugabytedb.YugabyteDBDataType_relocated\",  \"condition\" : {    \"typeReachable\" : \"org.jooq.impl.SQLDataType_relocated\"  }}, {  \"name\" : \"a\",  \"condition\" : {    \"typeReachable\" : \"b\"  }} ]");
			}
		}

	}

	@Test
	void shouldTransformResourceConfig() throws IOException {
		// language=json
		var orig = """
				{
				   "bundles": [
				     {
				       "name": "org.neo4j.cypherdsl.core.messages"
				     },
				     {
				       "name": "blah"
				     },
				     {
				       "whatever": "org.neo4j.cypherdsl.core.messages"
				     }
				   ]
				 }
				""";

		var transformer = new ReflectConfigTransformer();
		try (var inputStream = new ByteArrayInputStream(orig.getBytes(StandardCharsets.UTF_8))) {
			transformer.processResource("test", inputStream, List.of(this.relocator), System.currentTimeMillis());
		}

		try (var outputStream = new ByteArrayOutputStream(); var jarOutputStream = new JarOutputStream(outputStream)) {
			transformer.modifyOutputStream(jarOutputStream);
			jarOutputStream.flush();
			jarOutputStream.finish();

			try (var jarInputStream = new JarInputStream(new ByteArrayInputStream(outputStream.toByteArray()))) {
				jarInputStream.getNextJarEntry();
				var transformed = new BufferedReader(new InputStreamReader(jarInputStream, StandardCharsets.UTF_8))
					.lines()
					.collect(Collectors.joining());
				assertThat(transformed).isEqualTo(
						"{  \"bundles\" : [ {    \"name\" : \"org.neo4j.cypherdsl.core.messages_relocated\"  }, {    \"name\" : \"blah\"  }, {    \"whatever\" : \"org.neo4j.cypherdsl.core.messages\"  } ]}");
			}
		}

	}

}
