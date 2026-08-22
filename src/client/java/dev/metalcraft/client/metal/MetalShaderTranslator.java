package dev.metalcraft.client.metal;

import static org.lwjgl.util.shaderc.Shaderc.shaderc_compilation_status_success;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_into_spv;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_initialize;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_release;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_auto_bind_uniforms;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_auto_map_locations;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_optimization_level;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_preserve_bindings;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_source_language;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_target_env;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_target_spirv;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_initialize;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_release;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_env_version_vulkan_1_2;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_fragment_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_vertex_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_optimization_level_performance;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_bytes;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_compilation_status;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_error_message;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_release;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_source_language_glsl;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_spirv_version_1_5;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_target_env_vulkan;
import static org.lwjgl.util.spvc.Spv.SpvDecorationBinding;
import static org.lwjgl.util.spvc.Spv.SpvExecutionModelFragment;
import static org.lwjgl.util.spvc.Spv.SpvExecutionModelVertex;
import static org.lwjgl.util.spvc.Spvc.SPVC_BACKEND_MSL;
import static org.lwjgl.util.spvc.Spvc.SPVC_CAPTURE_MODE_COPY;
import static org.lwjgl.util.spvc.Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM;
import static org.lwjgl.util.spvc.Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_DECORATION_BINDING;
import static org.lwjgl.util.spvc.Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y;
import static org.lwjgl.util.spvc.Spvc.SPVC_COMPILER_OPTION_MSL_VERSION;
import static org.lwjgl.util.spvc.Spvc.SPVC_MSL_PLATFORM_MACOS;
import static org.lwjgl.util.spvc.Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE;
import static org.lwjgl.util.spvc.Spvc.SPVC_RESOURCE_TYPE_SEPARATE_IMAGE;
import static org.lwjgl.util.spvc.Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER;
import static org.lwjgl.util.spvc.Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE;
import static org.lwjgl.util.spvc.Spvc.SPVC_RESOURCE_TYPE_SUBPASS_INPUT;
import static org.lwjgl.util.spvc.Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER;
import static org.lwjgl.util.spvc.Spvc.SPVC_SUCCESS;
import static org.lwjgl.util.spvc.Spvc.spvc_compiler_compile;
import static org.lwjgl.util.spvc.Spvc.spvc_compiler_create_compiler_options;
import static org.lwjgl.util.spvc.Spvc.spvc_compiler_create_shader_resources;
import static org.lwjgl.util.spvc.Spvc.spvc_compiler_get_decoration;
import static org.lwjgl.util.spvc.Spvc.spvc_compiler_get_cleansed_entry_point_name;
import static org.lwjgl.util.spvc.Spvc.spvc_compiler_install_compiler_options;
import static org.lwjgl.util.spvc.Spvc.spvc_compiler_options_set_bool;
import static org.lwjgl.util.spvc.Spvc.spvc_compiler_options_set_uint;
import static org.lwjgl.util.spvc.Spvc.spvc_context_create;
import static org.lwjgl.util.spvc.Spvc.spvc_context_create_compiler;
import static org.lwjgl.util.spvc.Spvc.spvc_context_destroy;
import static org.lwjgl.util.spvc.Spvc.spvc_context_get_last_error_string;
import static org.lwjgl.util.spvc.Spvc.spvc_context_parse_spirv;
import static org.lwjgl.util.spvc.Spvc.spvc_resources_get_resource_list_for_type;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.SpvcReflectedResource;
import org.lwjgl.system.MemoryUtil;

/** Compiles expanded GLSL to Vulkan SPIR-V, then translates that module to macOS MSL. */
public final class MetalShaderTranslator {
	private static final int MSL_VERSION_2_4 = 20400;
	private static final String SEPARATE_SHADER_OBJECTS_EXTENSION = "#extension GL_ARB_separate_shader_objects : enable\n";
	private static final String INTERPOLATION_QUALIFIERS = "(?:(?:flat|smooth|noperspective|centroid|sample|invariant)\\h+)*";
	private static final Pattern VERTEX_OUTPUT = Pattern.compile(
		"(?m)^(\\h*)(" + INTERPOLATION_QUALIFIERS + ")out(\\h+[^;\\r\\n]+?\\h+)([A-Za-z_]\\w*)(\\h*(?:\\[[^;\\r\\n]+\\])?\\h*;)"
	);
	private static final Pattern FRAGMENT_INPUT = Pattern.compile(
		"(?m)^(\\h*)(" + INTERPOLATION_QUALIFIERS + ")in(\\h+[^;\\r\\n]+?\\h+)([A-Za-z_]\\w*)(\\h*(?:\\[[^;\\r\\n]+\\])?\\h*;)"
	);

	public enum Stage {
		VERTEX(shaderc_glsl_vertex_shader, SpvExecutionModelVertex),
		FRAGMENT(shaderc_glsl_fragment_shader, SpvExecutionModelFragment);

		private final int shadercKind;
		private final int executionModel;

		Stage(final int shadercKind, final int executionModel) {
			this.shadercKind = shadercKind;
			this.executionModel = executionModel;
		}
	}

	/**
	 * A translated stage. The returned SPIR-V byte array is always a defensive copy.
	 *
	 * <p>The two slot masks say which resource slots this stage actually declares, one bit per slot.
	 * They exist so the render path can bind a resource to the stages that read it instead of to
	 * both: Metal's vertex and fragment argument tables are separate, so binding a texture the
	 * fragment stage alone samples was costing an encoder call per bind for nothing.
	 *
	 * <p>There is no separate sampler mask. A texel buffer reflects as a sampled image but compiles
	 * to a texture with no sampler, so a sampler mask taken from reflection is an over-approximation
	 * on exactly the slots where it would differ - and those slots are never given a sampler by the
	 * render path anyway. Every slot that does take one takes it in the same stages as its texture.
	 */
	public record Translation(
		Stage stage,
		String sourceName,
		String entryPoint,
		byte[] spirv,
		String metalSource,
		int bufferSlots,
		int textureSlots
	) {
		public Translation {
			Objects.requireNonNull(stage, "stage");
			Objects.requireNonNull(sourceName, "sourceName");
			Objects.requireNonNull(entryPoint, "entryPoint");
			Objects.requireNonNull(spirv, "spirv");
			Objects.requireNonNull(metalSource, "metalSource");
			spirv = spirv.clone();
		}

		@Override
		public byte[] spirv() {
			return this.spirv.clone();
		}
	}

	public record PipelineTranslation(Translation vertex, Translation fragment) {
		public PipelineTranslation {
			Objects.requireNonNull(vertex, "vertex");
			Objects.requireNonNull(fragment, "fragment");
			if (vertex.stage() != Stage.VERTEX || fragment.stage() != Stage.FRAGMENT) {
				throw new IllegalArgumentException("A translated render pipeline requires vertex and fragment stages");
			}
		}
	}

	private MetalShaderTranslator() {
	}

	public static PipelineTranslation translatePipeline(
		final String vertexSource,
		final String vertexSourceName,
		final String fragmentSource,
		final String fragmentSourceName
	) {
		InterstageSources linkedSources = linkInterstageLocations(vertexSource, fragmentSource);
		return new PipelineTranslation(
			translate(linkedSources.vertex(), Stage.VERTEX, vertexSourceName),
			translate(linkedSources.fragment(), Stage.FRAGMENT, fragmentSourceName)
		);
	}

	private static InterstageSources linkInterstageLocations(final String vertexSource, final String fragmentSource) {
		Map<String, Integer> locations = new LinkedHashMap<>();
		Matcher vertexMatcher = VERTEX_OUTPUT.matcher(vertexSource);
		StringBuilder vertex = new StringBuilder(vertexSource.length() + 128);
		int location = 0;
		while (vertexMatcher.find()) {
			int mappedLocation = location++;
			locations.putIfAbsent(vertexMatcher.group(4), mappedLocation);
			String declaration = vertexMatcher.group(1) + "layout(location = " + mappedLocation + ") "
				+ vertexMatcher.group(2) + "out" + vertexMatcher.group(3) + vertexMatcher.group(4) + vertexMatcher.group(5);
			vertexMatcher.appendReplacement(vertex, Matcher.quoteReplacement(declaration));
		}
		vertexMatcher.appendTail(vertex);

		Matcher fragmentMatcher = FRAGMENT_INPUT.matcher(fragmentSource);
		StringBuilder fragment = new StringBuilder(fragmentSource.length() + 128);
		while (fragmentMatcher.find()) {
			Integer mappedLocation = locations.get(fragmentMatcher.group(4));
			if (mappedLocation == null) {
				continue;
			}
			String declaration = fragmentMatcher.group(1) + "layout(location = " + mappedLocation + ") "
				+ fragmentMatcher.group(2) + "in" + fragmentMatcher.group(3) + fragmentMatcher.group(4) + fragmentMatcher.group(5);
			fragmentMatcher.appendReplacement(fragment, Matcher.quoteReplacement(declaration));
		}
		fragmentMatcher.appendTail(fragment);
		if (locations.isEmpty()) {
			return new InterstageSources(vertex.toString(), fragment.toString());
		}
		return new InterstageSources(
			enableSeparateShaderObjects(vertex.toString()),
			enableSeparateShaderObjects(fragment.toString())
		);
	}

	private static String enableSeparateShaderObjects(final String source) {
		if (source.contains("GL_ARB_separate_shader_objects")) {
			return source;
		}
		int version = source.indexOf("#version");
		int lineEnd = version < 0 ? -1 : source.indexOf('\n', version);
		if (lineEnd < 0) {
			return SEPARATE_SHADER_OBJECTS_EXTENSION + source;
		}
		return source.substring(0, lineEnd + 1) + SEPARATE_SHADER_OBJECTS_EXTENSION + source.substring(lineEnd + 1);
	}

	private record InterstageSources(String vertex, String fragment) {
	}

	public static Translation translate(final String source, final Stage stage, final String sourceName) {
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(stage, "stage");
		Objects.requireNonNull(sourceName, "sourceName");
		if (source.isBlank()) {
			throw new IllegalArgumentException("GLSL source cannot be blank");
		}
		if (sourceName.isBlank()) {
			throw new IllegalArgumentException("A shader source name cannot be blank");
		}

		String shaderSource = stage == Stage.VERTEX ? source.replace("gl_VertexID", "gl_VertexIndex") : source;
		long compiler = shaderc_compiler_initialize();
		if (compiler == MemoryUtil.NULL) {
			throw failure(stage, sourceName, "shaderc could not create a compiler", null);
		}
		long options = MemoryUtil.NULL;
		long result = MemoryUtil.NULL;
		try {
			options = shaderc_compile_options_initialize();
			if (options == MemoryUtil.NULL) {
				throw failure(stage, sourceName, "shaderc could not create compiler options", null);
			}
			shaderc_compile_options_set_source_language(options, shaderc_source_language_glsl);
			shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
			shaderc_compile_options_set_target_spirv(options, shaderc_spirv_version_1_5);
			shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
			shaderc_compile_options_set_auto_bind_uniforms(options, true);
			shaderc_compile_options_set_auto_map_locations(options, true);
			shaderc_compile_options_set_preserve_bindings(options, true);

			result = shaderc_compile_into_spv(compiler, shaderSource, stage.shadercKind, sourceName, "main", options);
			if (result == MemoryUtil.NULL) {
				throw failure(stage, sourceName, "shaderc returned no compilation result", null);
			}
			if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
				throw failure(stage, sourceName, "GLSL compilation failed", shaderc_result_get_error_message(result));
			}

			ByteBuffer nativeSpirv = shaderc_result_get_bytes(result);
			if (nativeSpirv == null || nativeSpirv.remaining() == 0 || nativeSpirv.remaining() % Integer.BYTES != 0) {
				throw failure(stage, sourceName, "shaderc returned malformed SPIR-V", null);
			}
			ByteBuffer spirvView = nativeSpirv.duplicate().order(ByteOrder.nativeOrder());
			byte[] spirv = new byte[spirvView.remaining()];
			spirvView.get(spirv);
			spirvView.rewind();
			return translateSpirv(spirvView.asIntBuffer(), spirv, stage, sourceName);
		} finally {
			if (result != MemoryUtil.NULL) {
				shaderc_result_release(result);
			}
			if (options != MemoryUtil.NULL) {
				shaderc_compile_options_release(options);
			}
			shaderc_compiler_release(compiler);
		}
	}

	private static Translation translateSpirv(
		final IntBuffer spirvWords,
		final byte[] spirv,
		final Stage stage,
		final String sourceName
	) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer contextPointer = stack.mallocPointer(1);
			int status = spvc_context_create(contextPointer);
			if (status != SPVC_SUCCESS) {
				throw failure(stage, sourceName, "SPIRV-Cross could not create a context (error " + status + ")", null);
			}
			long context = contextPointer.get(0);
			try {
				PointerBuffer parsedIrPointer = stack.mallocPointer(1);
				checkSpvc(
					spvc_context_parse_spirv(context, spirvWords, spirvWords.remaining(), parsedIrPointer),
					context,
					stage,
					sourceName,
					"SPIR-V parsing"
				);

				PointerBuffer compilerPointer = stack.mallocPointer(1);
				checkSpvc(
					spvc_context_create_compiler(
						context,
						SPVC_BACKEND_MSL,
						parsedIrPointer.get(0),
						SPVC_CAPTURE_MODE_COPY,
						compilerPointer
					),
					context,
					stage,
					sourceName,
					"MSL compiler creation"
				);
				long compiler = compilerPointer.get(0);

				PointerBuffer optionsPointer = stack.mallocPointer(1);
				checkSpvc(spvc_compiler_create_compiler_options(compiler, optionsPointer), context, stage, sourceName, "MSL option creation");
				long options = optionsPointer.get(0);
				checkSpvc(spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_VERSION, MSL_VERSION_2_4), context, stage, sourceName, "MSL version selection");
				checkSpvc(spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_PLATFORM, SPVC_MSL_PLATFORM_MACOS), context, stage, sourceName, "MSL platform selection");
				checkSpvc(spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_MSL_ENABLE_DECORATION_BINDING, true), context, stage, sourceName, "MSL resource binding preservation");
				if (stage == Stage.VERTEX) {
					checkSpvc(spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true), context, stage, sourceName, "Vulkan-to-Metal vertex coordinates");
				}
				checkSpvc(spvc_compiler_install_compiler_options(compiler, options), context, stage, sourceName, "MSL option installation");

				PointerBuffer sourcePointer = stack.mallocPointer(1);
				checkSpvc(spvc_compiler_compile(compiler, sourcePointer), context, stage, sourceName, "MSL translation");
				String entryPoint = spvc_compiler_get_cleansed_entry_point_name(compiler, "main", stage.executionModel);
				if (entryPoint == null || entryPoint.isBlank()) {
					throw failure(stage, sourceName, "SPIRV-Cross did not expose the translated entry point", null);
				}
				String metalSource = MemoryUtil.memUTF8Safe(sourcePointer.get(0));
				if (metalSource == null || metalSource.isBlank()) {
					throw failure(stage, sourceName, "SPIRV-Cross returned empty MSL", null);
				}
				PointerBuffer resourcesPointer = stack.mallocPointer(1);
				checkSpvc(spvc_compiler_create_shader_resources(compiler, resourcesPointer), context, stage, sourceName, "MSL resource reflection");
				long resources = resourcesPointer.get(0);
				int bufferSlots = slotMask(compiler, resources, context, stage, sourceName,
					SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, SPVC_RESOURCE_TYPE_STORAGE_BUFFER);
				int textureSlots = slotMask(compiler, resources, context, stage, sourceName,
					SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, SPVC_RESOURCE_TYPE_SEPARATE_IMAGE,
					SPVC_RESOURCE_TYPE_STORAGE_IMAGE, SPVC_RESOURCE_TYPE_SUBPASS_INPUT);
				return new Translation(stage, sourceName, entryPoint, spirv, metalSource, bufferSlots, textureSlots);
			} finally {
				spvc_context_destroy(context);
			}
		}
	}

	/**
	 * Collects the binding numbers this module declares for the given resource types into a bitmask.
	 *
	 * <p>Declared rather than active on purpose. The mask decides which stages a resource is bound
	 * to, so a slot missing from it is never bound; being generous costs one redundant encoder call,
	 * while being stingy renders the wrong thing. A binding above the sixteen slots Metal's argument
	 * tables hold cannot be one of ours, so it is ignored rather than corrupting the mask.
	 */
	private static int slotMask(
		final long compiler,
		final long resources,
		final long context,
		final Stage stage,
		final String sourceName,
		final int... resourceTypes
	) {
		int mask = 0;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			for (int resourceType : resourceTypes) {
				PointerBuffer list = stack.mallocPointer(1);
				PointerBuffer count = stack.mallocPointer(1);
				checkSpvc(
					spvc_resources_get_resource_list_for_type(resources, resourceType, list, count),
					context, stage, sourceName, "MSL resource enumeration"
				);
				int size = (int)count.get(0);
				if (size <= 0) {
					continue;
				}
				SpvcReflectedResource.Buffer reflected = SpvcReflectedResource.create(list.get(0), size);
				for (int index = 0; index < size; index++) {
					int binding = spvc_compiler_get_decoration(compiler, reflected.get(index).id(), SpvDecorationBinding);
					if (binding >= 0 && binding < Integer.SIZE) {
						mask |= 1 << binding;
					}
				}
			}
		}
		return mask;
	}

	private static void checkSpvc(
		final int status,
		final long context,
		final Stage stage,
		final String sourceName,
		final String operation
	) {
		if (status == SPVC_SUCCESS) {
			return;
		}
		throw failure(stage, sourceName, operation + " failed (error " + status + ")", spvc_context_get_last_error_string(context));
	}

	private static IllegalArgumentException failure(
		final Stage stage,
		final String sourceName,
		final String summary,
		final String details
	) {
		String message = "Failed to translate " + stage.name().toLowerCase() + " shader " + sourceName + ": " + summary;
		if (details != null && !details.isBlank()) {
			message += System.lineSeparator() + details.strip();
		}
		return new IllegalArgumentException(message);
	}
}
