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

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.apache.maven.plugins.shade.relocation.Relocator;
import org.apache.maven.plugins.shade.resource.ReproducibleResourceTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transforms {@literal native-image.properties} files, trying to find classes in that are
 * initialized at run- or build-time and see if they are relocated.
 *
 * @author Michael J. Simons
 */
public final class NativeImagePropertiesTransformer implements ReproducibleResourceTransformer {

	private static final Logger LOGGER = LoggerFactory.getLogger(NativeImagePropertiesTransformer.class);

	private final Map<String, Properties> resources = new HashMap<>();

	private long time = Long.MIN_VALUE;

	/**
	 * Required for the Maven shade plugin to work.
	 */
	public NativeImagePropertiesTransformer() {
	}

	@Override
	public void processResource(String resource, InputStream is, List<Relocator> relocators, long time)
			throws IOException {

		LOGGER.info("Processing {}", resource);

		boolean changed = false;

		var properties = new Properties();
		properties.load(is);
		var argsProperty = properties.getProperty("Args");
		if (argsProperty != null) {
			var args = argsProperty.split("\\s");
			var newArgsProperty = new StringBuilder();
			for (var arg : args) {
				if (arg.startsWith("--initialize-at-run-time") || arg.startsWith("--initialize-at-build-time")) {
					var option = arg.substring(0, arg.indexOf("="));
					newArgsProperty.append(option).append("=");

					var types = arg.substring(arg.indexOf("=") + 1).split(",");
					for (var type : types) {
						var relocatedType = type.trim();
						for (var relocator : relocators) {
							if (relocator.canRelocateClass(type)) {
								relocatedType = relocator.relocateClass(type);
								break;
							}
						}
						newArgsProperty.append(relocatedType).append(",");
					}
					newArgsProperty.setCharAt(newArgsProperty.length() - 1, ' ');
					changed = true;
					if (time > this.time) {
						this.time = time;
					}
				}
				else {
					newArgsProperty.append(arg).append(" ");
				}
			}

			if (changed) {
				properties.setProperty("Args", newArgsProperty.substring(0, newArgsProperty.length() - 1));
			}
		}
		this.resources.put(resource, properties);
	}

	@Override
	public boolean canTransformResource(String resource) {
		return resource.startsWith("META-INF/native-image/") && resource.endsWith("native-image.properties");
	}

	@Override
	@SuppressWarnings("deprecation")
	public void processResource(String resource, InputStream is, List<Relocator> relocators) throws IOException {
		processResource(resource, is, relocators, 0);
	}

	@Override
	public boolean hasTransformedResource() {
		return !this.resources.isEmpty();
	}

	@Override
	public void modifyOutputStream(JarOutputStream os) throws IOException {
		for (Map.Entry<String, Properties> e : this.resources.entrySet()) {
			var resource = e.getKey();
			var properties = e.getValue();

			var jarEntry = new JarEntry(resource);
			jarEntry.setTime(this.time);
			os.putNextEntry(jarEntry);

			properties.store(os, "Relocated by " + this.getClass().getName());
		}
	}

}
