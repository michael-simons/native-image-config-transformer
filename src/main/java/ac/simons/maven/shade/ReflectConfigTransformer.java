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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.maven.plugins.shade.relocation.Relocator;
import org.apache.maven.plugins.shade.resource.ReproducibleResourceTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tries to find resources or reflection configuration of native image and tries to
 * relocate type names.
 *
 * @author Michael J. Simons
 */
public final class ReflectConfigTransformer implements ReproducibleResourceTransformer {

	private static final Logger LOGGER = LoggerFactory.getLogger(ReflectConfigTransformer.class);

	private final Map<String, Object> resources = new HashMap<>();

	private long time = Long.MIN_VALUE;

	private final ObjectMapper objectMapper;

	/**
	 * Creates a new instance of this transformer and configures the objectmapper.
	 */
	public ReflectConfigTransformer() {
		this.objectMapper = new ObjectMapper();
		this.objectMapper.disable(JsonParser.Feature.AUTO_CLOSE_SOURCE);
		this.objectMapper.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
		this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
	}

	@Override
	public void processResource(String resource, InputStream is, List<Relocator> relocators, long time)
			throws IOException {

		LOGGER.info("Processing {}", resource);

		var parser = this.objectMapper.createParser(is);
		parser.nextToken();
		if (parser.isExpectedStartArrayToken()) {
			transformList(resource, relocators, time, parser);
		}
		else if (parser.isExpectedStartObjectToken()) {
			transformMap(resource, relocators, time, parser);
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private void transformMap(String resource, List<Relocator> relocators, long time, JsonParser parser)
			throws IOException {

		Map newConfig;
		var config = parser.readValueAs(Map.class);
		if (config.get("bundles") instanceof List bundles) {
			newConfig = new HashMap(config);
			var newBundles = new ArrayList<>();
			for (Object bundle : bundles) {
				if (bundle instanceof Map m && m.containsKey("name")) {
					var newBundle = new HashMap<>(m);
					newBundle.put("name", relocate(relocators, (String) m.get("name")));
					newBundles.add(newBundle);
				}
				else {
					newBundles.add(bundle);
				}
			}
			newConfig.put("bundles", newBundles);
		}
		else {
			newConfig = config;
		}

		this.resources.put(resource, newConfig);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private void transformList(String resource, List<Relocator> relocators, long time, JsonParser parser)
			throws IOException {

		var newConfig = new ArrayList<>();
		var config = parser.readValueAs(List.class);
		for (Object entry : config) {
			if (!(entry instanceof Map m)) {
				newConfig.add(entry);
			}
			else {
				var newEntry = new HashMap<>(m);
				if (newEntry.get("name") instanceof String type) {
					newEntry.put("name", relocate(relocators, type));
				}
				if (newEntry.get("condition") instanceof Map condition
						&& condition.get("typeReachable") instanceof String type) {

					var newCondition = new HashMap<>(condition);
					newCondition.put("typeReachable", relocate(relocators, type));
					newEntry.put("condition", newCondition);
				}

				if (time > this.time) {
					this.time = time;
				}
				newConfig.add(newEntry);
			}
		}
		this.resources.put(resource, newConfig);
	}

	String relocate(List<Relocator> relocators, String type) {
		for (var relocator : relocators) {
			if (relocator.canRelocateClass(type)) {
				return relocator.relocateClass(type);
			}
		}
		return type;
	}

	@Override
	public boolean canTransformResource(String resource) {
		return resource.startsWith("META-INF/native-image/") && resource.endsWith(".json");
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
		for (Map.Entry<String, Object> e : this.resources.entrySet()) {
			var resource = e.getKey();
			var config = e.getValue();

			var jarEntry = new JarEntry(resource);
			jarEntry.setTime(this.time);
			os.putNextEntry(jarEntry);

			this.objectMapper.writeValue(os, config);
		}
	}

}
