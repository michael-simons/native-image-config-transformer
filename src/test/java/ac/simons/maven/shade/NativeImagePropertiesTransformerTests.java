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
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import org.apache.maven.plugins.shade.relocation.Relocator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NativeImagePropertiesTransformerTests {

	@Test
	void shouldTransformResources() throws IOException {
		var orig = """
					Args = \\
					    -H:ResourceConfigurationResources=${.}/resources-config.json \\
					    -H:ReflectionConfigurationResources=${.}/reflection-config.json \\
					    --initialize-at-run-time=a.b.c \\
					    --initialize-at-build-time=a,b,c \\
					    -H:ReflectionConfigurationResources=${.}/reflection-config.json \\
				""";

		var relocator = new Relocator() {
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
				return !"a".equals(clazz);
			}

			@Override
			public String relocateClass(String clazz) {
				return clazz + "_rel";
			}

			@Override
			public String applyToSourceContent(String sourceContent) {
				return "";
			}
		};

		var transformer = new NativeImagePropertiesTransformer();
		try (var inputStream = new ByteArrayInputStream(orig.getBytes(StandardCharsets.UTF_8))) {
			transformer.processResource("test", inputStream, List.of(relocator), System.currentTimeMillis());
		}
		try (var outputStream = new ByteArrayOutputStream(); var jarOutputStream = new JarOutputStream(outputStream)) {
			transformer.modifyOutputStream(jarOutputStream);
			jarOutputStream.flush();
			jarOutputStream.finish();

			try (var jarInputStream = new JarInputStream(new ByteArrayInputStream(outputStream.toByteArray()))) {
				jarInputStream.getNextJarEntry();
				var lines = new BufferedReader(new InputStreamReader(jarInputStream, StandardCharsets.UTF_8)).lines()
					.toList();
				assertThat(lines).containsAll(List.of(
						"#Relocated by ac.simons.maven.shade.NativeImagePropertiesTransformer",
						"Args=-H\\:ResourceConfigurationResources\\=${.}/resources-config.json -H\\:ReflectionConfigurationResources\\=${.}/reflection-config.json --initialize-at-run-time\\=a.b.c_rel --initialize-at-build-time\\=a,b_rel,c_rel -H\\:ReflectionConfigurationResources\\=${.}/reflection-config.json"));
			}
		}
	}

}
