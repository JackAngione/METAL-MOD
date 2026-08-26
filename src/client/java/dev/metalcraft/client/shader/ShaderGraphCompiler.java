package dev.metalcraft.client.shader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/** Validates declared shader dataflow and compiles it into an execution graph. */
public final class ShaderGraphCompiler {
	private static final Set<String> RESERVED_TARGETS = Set.of("depth", "drawable");

	public enum LoadAction {
		LOAD,
		DONT_CARE
	}

	public enum StoreAction {
		STORE,
		DONT_CARE
	}

	public record WriteDecision(String target, LoadAction loadAction, StoreAction storeAction) {
		public WriteDecision {
			Objects.requireNonNull(target, "target");
			Objects.requireNonNull(loadAction, "loadAction");
			Objects.requireNonNull(storeAction, "storeAction");
		}
	}

	public record CompiledPass(ShaderPack.Pass declaration, int groupIndex, List<WriteDecision> writes) {
		public CompiledPass {
			Objects.requireNonNull(declaration, "declaration");
			if (groupIndex < 0) {
				throw new IllegalArgumentException("groupIndex cannot be negative");
			}
			writes = List.copyOf(writes);
		}
	}

	public record TargetInfo(
		ShaderPack.Target declaration,
		Optional<String> producer,
		List<String> consumers,
		boolean memoryless
	) {
		public TargetInfo {
			Objects.requireNonNull(declaration, "declaration");
			Objects.requireNonNull(producer, "producer");
			consumers = List.copyOf(consumers);
		}
	}

	/** Consecutive render passes in one group share one encoder; compute groups always contain one pass. */
	public record PassGroup(int index, List<String> passes) {
		public PassGroup {
			if (index < 0) {
				throw new IllegalArgumentException("index cannot be negative");
			}
			passes = List.copyOf(passes);
			if (passes.isEmpty()) {
				throw new IllegalArgumentException("A pass group cannot be empty");
			}
		}
	}

	public record CompiledGraph(
		List<CompiledPass> passes,
		Map<String, TargetInfo> targets,
		List<PassGroup> groups
	) {
		public CompiledGraph {
			passes = List.copyOf(passes);
			targets = immutableMap(targets);
			groups = List.copyOf(groups);
		}
	}

	/** An invalid graph declaration. Messages name the involved passes and target where applicable. */
	public static final class CompileException extends IllegalArgumentException {
		public CompileException(final String message) {
			super(message);
		}
	}

	private record Edge(String producer, String consumer) {
	}

	private record Shape(ShaderPack.Extent extent, int layers) {
	}

	private static final class Usage {
		private final Set<String> sampled = new LinkedHashSet<>();
		private final Set<String> tiled = new LinkedHashSet<>();

		List<String> consumers() {
			Set<String> combined = new LinkedHashSet<>(this.sampled);
			combined.addAll(this.tiled);
			return List.copyOf(combined);
		}
	}

	private ShaderGraphCompiler() {
	}

	public static CompiledGraph compile(final ShaderPack pack) {
		Objects.requireNonNull(pack, "pack");
		return compile(pack.manifest());
	}

	public static CompiledGraph compile(final ShaderPack.Manifest manifest) {
		Objects.requireNonNull(manifest, "manifest");
		if (manifest.passes().isEmpty()) {
			throw new CompileException("A shader graph requires at least one pass");
		}
		Map<String, ShaderPack.Pass> byId = new LinkedHashMap<>();
		Map<String, Integer> order = new HashMap<>();
		for (int index = 0; index < manifest.passes().size(); index++) {
			ShaderPack.Pass pass = manifest.passes().get(index);
			if (byId.putIfAbsent(pass.id(), pass) != null) {
				throw new CompileException("Duplicate pass ID '" + pass.id() + "'");
			}
			order.put(pass.id(), index);
		}

		validateReferencesAndKinds(manifest, byId);
		Map<String, String> roots = mergeRoots(byId);
		Map<String, List<String>> producers = producers(manifest.passes());
		for (Map.Entry<String, List<String>> entry : producers.entrySet()) {
			if (entry.getValue().size() > 1) {
				throw new CompileException(
					"Target '" + entry.getKey() + "' has ambiguous producers " + entry.getValue()
				);
			}
		}

		Map<String, Set<String>> dependencies = new LinkedHashMap<>();
		Map<Edge, Set<String>> edgeLabels = new HashMap<>();
		Map<String, Usage> usages = new LinkedHashMap<>();
		for (String passId : byId.keySet()) {
			dependencies.put(passId, new LinkedHashSet<>());
		}
		for (String target : manifest.targets().keySet()) {
			usages.put(target, new Usage());
		}
		for (String target : RESERVED_TARGETS) {
			usages.put(target, new Usage());
		}

		for (ShaderPack.Pass pass : manifest.passes()) {
			if (pass.writes().isEmpty()) {
				throw new CompileException("Pass '" + pass.id() + "' must write at least one target");
			}
			if (pass.mergeWith() != null) {
				addDependency(dependencies, edgeLabels, pass.mergeWith(), pass.id(), "merge_with");
			}
			for (String target : pass.reads()) {
				usages.get(target).sampled.add(pass.id());
				validateSampledRead(manifest, pass, target, producers, roots);
				addProducerDependency(dependencies, edgeLabels, producers, target, pass.id());
			}
			for (String target : pass.tileReads()) {
				usages.get(target).tiled.add(pass.id());
				validateTileRead(manifest, pass, target, producers, roots);
				addProducerDependency(dependencies, edgeLabels, producers, target, pass.id());
			}
		}

		List<String> allPassIds = List.copyOf(byId.keySet());
		topologicalOrder(allPassIds, dependencies, order, edgeLabels, "Pass graph contains a cycle");
		validateGroupAttachments(manifest, byId, roots);

		Map<String, List<String>> members = new LinkedHashMap<>();
		for (String passId : byId.keySet()) {
			members.computeIfAbsent(roots.get(passId), ignored -> new ArrayList<>()).add(passId);
		}
		Map<String, Set<String>> groupDependencies = new LinkedHashMap<>();
		for (String root : members.keySet()) {
			groupDependencies.put(root, new LinkedHashSet<>());
		}
		for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
			String consumerRoot = roots.get(entry.getKey());
			for (String dependency : entry.getValue()) {
				String producerRoot = roots.get(dependency);
				if (!producerRoot.equals(consumerRoot)) {
					groupDependencies.get(consumerRoot).add(producerRoot);
				}
			}
		}
		Map<String, Integer> groupOrder = new HashMap<>();
		for (Map.Entry<String, List<String>> entry : members.entrySet()) {
			groupOrder.put(entry.getKey(), entry.getValue().stream().mapToInt(order::get).min().orElseThrow());
		}
		List<String> orderedRoots = topologicalOrder(
			List.copyOf(members.keySet()),
			groupDependencies,
			groupOrder,
			Map.of(),
			"Merged pass groups cannot be scheduled contiguously"
		);

		Map<String, Boolean> memoryless = memorylessTargets(manifest, producers, usages, roots);
		Map<String, Integer> passGroups = new HashMap<>();
		List<PassGroup> compiledGroups = new ArrayList<>();
		List<String> executionOrder = new ArrayList<>();
		for (String root : orderedRoots) {
			Set<String> memberSet = Set.copyOf(members.get(root));
			Map<String, Set<String>> internalDependencies = new LinkedHashMap<>();
			for (String passId : members.get(root)) {
				Set<String> internal = new LinkedHashSet<>(dependencies.get(passId));
				internal.retainAll(memberSet);
				internalDependencies.put(passId, internal);
			}
			List<String> orderedMembers = topologicalOrder(
				members.get(root), internalDependencies, order, edgeLabels, "Merged pass group contains a cycle"
			);
			int groupIndex = compiledGroups.size();
			for (String passId : orderedMembers) {
				passGroups.put(passId, groupIndex);
			}
			compiledGroups.add(new PassGroup(groupIndex, orderedMembers));
			executionOrder.addAll(orderedMembers);
		}

		List<CompiledPass> compiledPasses = new ArrayList<>();
		for (String passId : executionOrder) {
			ShaderPack.Pass pass = byId.get(passId);
			List<WriteDecision> writes = new ArrayList<>();
			for (String target : pass.writes()) {
				LoadAction load = pass.reads().contains(target) ? LoadAction.LOAD : LoadAction.DONT_CARE;
				writes.add(new WriteDecision(target, load, storeAction(manifest, target, usages, roots, passId)));
			}
			compiledPasses.add(new CompiledPass(pass, passGroups.get(passId), writes));
		}

		Map<String, TargetInfo> targetInfos = new LinkedHashMap<>();
		for (Map.Entry<String, ShaderPack.Target> entry : manifest.targets().entrySet()) {
			List<String> targetProducers = producers.getOrDefault(entry.getKey(), List.of());
			targetInfos.put(entry.getKey(), new TargetInfo(
				entry.getValue(),
				targetProducers.isEmpty() ? Optional.empty() : Optional.of(targetProducers.getFirst()),
				usages.get(entry.getKey()).consumers(),
				memoryless.get(entry.getKey())
			));
		}
		return new CompiledGraph(compiledPasses, targetInfos, compiledGroups);
	}

	private static void validateReferencesAndKinds(
		final ShaderPack.Manifest manifest,
		final Map<String, ShaderPack.Pass> passes
	) {
		for (ShaderPack.Pass pass : manifest.passes()) {
			if (pass.mergeWith() != null) {
				ShaderPack.Pass merged = passes.get(pass.mergeWith());
				if (merged == null) {
					throw new CompileException("Pass '" + pass.id() + "' merges with unknown pass '" + pass.mergeWith() + "'");
				}
				if (pass.kind() == ShaderPack.PassKind.COMPUTE || merged.kind() == ShaderPack.PassKind.COMPUTE) {
					throw new CompileException("Pass '" + pass.id() + "' cannot merge with compute pass '" + merged.id() + "'");
				}
			}
			if (pass.kind() != ShaderPack.PassKind.GEOMETRY && !pass.geometry().isEmpty()) {
				throw new CompileException("Only a geometry pass may declare geometry: pass '" + pass.id() + "'");
			}
			if (pass.kind() == ShaderPack.PassKind.COMPUTE && (!pass.tileReads().isEmpty() || pass.mergeWith() != null)) {
				throw new CompileException("Compute pass '" + pass.id() + "' cannot tile-read or merge");
			}
			for (String target : pass.reads()) {
				validateReference(manifest, pass.id(), target, "read");
				if (target.equals("drawable")) {
					throw new CompileException("Pass '" + pass.id() + "' cannot sample reserved target 'drawable'");
				}
			}
			for (String target : pass.tileReads()) {
				validateReference(manifest, pass.id(), target, "tile-read");
				if (target.equals("drawable")) {
					throw new CompileException("Pass '" + pass.id() + "' cannot tile-read reserved target 'drawable'");
				}
			}
			for (String target : pass.writes()) {
				validateReference(manifest, pass.id(), target, "write");
				ShaderPack.Target declaration = manifest.targets().get(target);
				if (pass.kind() == ShaderPack.PassKind.COMPUTE
					&& (target.equals("depth") || target.equals("drawable") || declaration != null && !declaration.format().isColor())) {
					throw new CompileException("Compute pass '" + pass.id() + "' cannot write attachment target '" + target + "'");
				}
			}
			validateAttachmentCount(manifest, List.of(pass));
		}
	}

	private static void validateReference(
		final ShaderPack.Manifest manifest,
		final String passId,
		final String target,
		final String operation
	) {
		if (!RESERVED_TARGETS.contains(target) && !manifest.targets().containsKey(target)) {
			throw new CompileException("Pass '" + passId + "' declares a " + operation + " of unknown target '" + target + "'");
		}
	}

	private static Map<String, String> mergeRoots(final Map<String, ShaderPack.Pass> passes) {
		Map<String, String> roots = new HashMap<>();
		for (String passId : passes.keySet()) {
			resolveRoot(passId, passes, roots, new LinkedHashSet<>());
		}
		return roots;
	}

	private static String resolveRoot(
		final String passId,
		final Map<String, ShaderPack.Pass> passes,
		final Map<String, String> roots,
		final Set<String> path
	) {
		String known = roots.get(passId);
		if (known != null) {
			return known;
		}
		if (!path.add(passId)) {
			throw new CompileException("merge_with cycle involving passes " + path);
		}
		String parent = passes.get(passId).mergeWith();
		String root = parent == null ? passId : resolveRoot(parent, passes, roots, path);
		path.remove(passId);
		roots.put(passId, root);
		return root;
	}

	private static Map<String, List<String>> producers(final List<ShaderPack.Pass> passes) {
		Map<String, List<String>> producers = new LinkedHashMap<>();
		for (ShaderPack.Pass pass : passes) {
			for (String target : pass.writes()) {
				producers.computeIfAbsent(target, ignored -> new ArrayList<>()).add(pass.id());
			}
		}
		return producers;
	}

	private static void validateSampledRead(
		final ShaderPack.Manifest manifest,
		final ShaderPack.Pass consumer,
		final String target,
		final Map<String, List<String>> producers,
		final Map<String, String> roots
	) {
		String producer = singleProducer(producers, target);
		ShaderPack.Target declaration = manifest.targets().get(target);
		if (declaration != null && declaration.lifetime() == ShaderPack.Lifetime.TRANSIENT) {
			throw new CompileException(producer == null
				? "Transient target '" + target + "' is sampled by pass '" + consumer.id() + "' but has no producer"
				: "Transient target '" + target + "' written by pass '" + producer + "' is sampled by pass '"
					+ consumer.id() + "'; use tile_reads and merge the passes");
		}
		if (producer != null && producer.equals(consumer.id())
			&& (declaration == null || declaration.lifetime() != ShaderPack.Lifetime.HISTORY)) {
			throw new CompileException("Pass '" + consumer.id() + "' cannot both sample and write target '" + target + "'");
		}
		if (producer != null && !producer.equals(consumer.id()) && roots.get(producer).equals(roots.get(consumer.id()))) {
			throw new CompileException(
				"Merged pass '" + consumer.id() + "' samples target '" + target + "' from pass '" + producer
					+ "'; merged consumers must use tile_reads"
			);
		}
	}

	private static void validateTileRead(
		final ShaderPack.Manifest manifest,
		final ShaderPack.Pass consumer,
		final String target,
		final Map<String, List<String>> producers,
		final Map<String, String> roots
	) {
		String producer = singleProducer(producers, target);
		if (producer == null) {
			throw new CompileException("Pass '" + consumer.id() + "' tile-reads target '" + target + "' with no producer");
		}
		if (producer.equals(consumer.id())) {
			throw new CompileException("Pass '" + consumer.id() + "' cannot tile-read its own write of target '" + target + "'");
		}
		if (consumer.mergeWith() == null || !roots.get(producer).equals(roots.get(consumer.id()))) {
			throw new CompileException(
				"Pass '" + consumer.id() + "' tile-reads target '" + target + "' from pass '" + producer
					+ "' without merging with its pass group"
			);
		}
		ShaderPack.Target declaration = manifest.targets().get(target);
		if (declaration != null && declaration.lifetime() == ShaderPack.Lifetime.TRANSIENT && declaration.layers() != 1) {
			throw new CompileException(
				"Transient layered target '" + target + "' written by pass '" + producer + "' and tile-read by pass '"
					+ consumer.id() + "' cannot be memoryless"
			);
		}
	}

	private static void addProducerDependency(
		final Map<String, Set<String>> dependencies,
		final Map<Edge, Set<String>> edgeLabels,
		final Map<String, List<String>> producers,
		final String target,
		final String consumer
	) {
		String producer = singleProducer(producers, target);
		if (producer != null && !producer.equals(consumer)) {
			addDependency(dependencies, edgeLabels, producer, consumer, target);
		}
	}

	private static String singleProducer(final Map<String, List<String>> producers, final String target) {
		List<String> targetProducers = producers.get(target);
		return targetProducers == null || targetProducers.isEmpty() ? null : targetProducers.getFirst();
	}

	private static void addDependency(
		final Map<String, Set<String>> dependencies,
		final Map<Edge, Set<String>> edgeLabels,
		final String producer,
		final String consumer,
		final String label
	) {
		dependencies.get(consumer).add(producer);
		edgeLabels.computeIfAbsent(new Edge(producer, consumer), ignored -> new LinkedHashSet<>()).add(label);
	}

	private static List<String> topologicalOrder(
		final List<String> nodes,
		final Map<String, Set<String>> dependencies,
		final Map<String, Integer> order,
		final Map<Edge, Set<String>> edgeLabels,
		final String cycleDescription
	) {
		Set<String> nodeSet = Set.copyOf(nodes);
		Map<String, Integer> indegree = new HashMap<>();
		Map<String, List<String>> outgoing = new HashMap<>();
		for (String node : nodes) {
			int degree = 0;
			for (String dependency : dependencies.getOrDefault(node, Set.of())) {
				if (nodeSet.contains(dependency)) {
					degree++;
					outgoing.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(node);
				}
			}
			indegree.put(node, degree);
		}
		PriorityQueue<String> ready = new PriorityQueue<>(Comparator.comparingInt(order::get));
		for (String node : nodes) {
			if (indegree.get(node) == 0) {
				ready.add(node);
			}
		}
		List<String> result = new ArrayList<>(nodes.size());
		while (!ready.isEmpty()) {
			String node = ready.remove();
			result.add(node);
			for (String consumer : outgoing.getOrDefault(node, List.of())) {
				int remaining = indegree.merge(consumer, -1, Integer::sum);
				if (remaining == 0) {
					ready.add(consumer);
				}
			}
		}
		if (result.size() != nodes.size()) {
			Set<String> remaining = new LinkedHashSet<>(nodes);
			remaining.removeAll(result);
			Set<String> targets = new LinkedHashSet<>();
			for (Map.Entry<Edge, Set<String>> entry : edgeLabels.entrySet()) {
				if (remaining.contains(entry.getKey().producer()) && remaining.contains(entry.getKey().consumer())) {
					for (String label : entry.getValue()) {
						if (!label.equals("merge_with")) {
							targets.add(label);
						}
					}
				}
			}
			throw new CompileException(cycleDescription + " involving passes " + remaining
				+ (targets.isEmpty() ? "" : " and targets " + targets));
		}
		return List.copyOf(result);
	}

	private static void validateGroupAttachments(
		final ShaderPack.Manifest manifest,
		final Map<String, ShaderPack.Pass> passes,
		final Map<String, String> roots
	) {
		Map<String, List<ShaderPack.Pass>> groups = new LinkedHashMap<>();
		for (ShaderPack.Pass pass : passes.values()) {
			groups.computeIfAbsent(roots.get(pass.id()), ignored -> new ArrayList<>()).add(pass);
		}
		for (List<ShaderPack.Pass> group : groups.values()) {
			validateAttachmentCount(manifest, group);
			Shape expected = null;
			String expectedTarget = null;
			for (ShaderPack.Pass pass : group) {
				if (pass.kind() == ShaderPack.PassKind.COMPUTE) {
					continue;
				}
				for (String target : pass.writes()) {
					Shape shape = shape(manifest, target);
					if (expected == null) {
						expected = shape;
						expectedTarget = target;
					} else if (!expected.equals(shape)) {
						throw new CompileException(
							"Pass group containing '" + group.getFirst().id() + "' has incompatible attachment targets '"
								+ expectedTarget + "' and '" + target + "'"
						);
					}
				}
			}
		}
	}

	private static void validateAttachmentCount(final ShaderPack.Manifest manifest, final List<ShaderPack.Pass> passes) {
		Set<String> colors = new HashSet<>();
		Set<String> depths = new HashSet<>();
		for (ShaderPack.Pass pass : passes) {
			if (pass.kind() == ShaderPack.PassKind.COMPUTE) {
				continue;
			}
			for (String target : pass.writes()) {
				ShaderPack.Target declaration = manifest.targets().get(target);
				if (target.equals("depth") || declaration != null && !declaration.format().isColor()) {
					depths.add(target);
				} else {
					colors.add(target);
				}
			}
		}
		if (colors.size() > 8) {
			throw new CompileException("Render pass group writes " + colors.size() + " color targets; Metal supports at most 8");
		}
		if (depths.size() > 1) {
			throw new CompileException("Render pass group has multiple depth/stencil targets " + depths);
		}
	}

	private static Shape shape(final ShaderPack.Manifest manifest, final String target) {
		ShaderPack.Target declaration = manifest.targets().get(target);
		return declaration == null
			? new Shape(new ShaderPack.Scale(1.0), 1)
			: new Shape(declaration.extent(), declaration.layers());
	}

	private static Map<String, Boolean> memorylessTargets(
		final ShaderPack.Manifest manifest,
		final Map<String, List<String>> producers,
		final Map<String, Usage> usages,
		final Map<String, String> roots
	) {
		Map<String, Boolean> result = new LinkedHashMap<>();
		for (Map.Entry<String, ShaderPack.Target> entry : manifest.targets().entrySet()) {
			String producer = singleProducer(producers, entry.getKey());
			Usage usage = usages.get(entry.getKey());
			boolean eligible = entry.getValue().lifetime() == ShaderPack.Lifetime.TRANSIENT
				&& entry.getValue().layers() == 1
				&& producer != null
				&& usage.sampled.isEmpty()
				&& !usage.tiled.isEmpty()
				&& usage.tiled.stream().allMatch(consumer -> roots.get(consumer).equals(roots.get(producer)));
			result.put(entry.getKey(), eligible);
		}
		return result;
	}

	private static StoreAction storeAction(
		final ShaderPack.Manifest manifest,
		final String target,
		final Map<String, Usage> usages,
		final Map<String, String> roots,
		final String producer
	) {
		if (target.equals("drawable")) {
			return StoreAction.STORE;
		}
		ShaderPack.Target declaration = manifest.targets().get(target);
		if (declaration != null && declaration.lifetime() == ShaderPack.Lifetime.HISTORY) {
			return StoreAction.STORE;
		}
		Usage usage = usages.get(target);
		if (!usage.sampled.isEmpty()) {
			return StoreAction.STORE;
		}
		for (String consumer : usage.tiled) {
			if (!roots.get(consumer).equals(roots.get(producer))) {
				return StoreAction.STORE;
			}
		}
		return StoreAction.DONT_CARE;
	}

	private static <K, V> Map<K, V> immutableMap(final Map<K, V> values) {
		return Collections.unmodifiableMap(new LinkedHashMap<>(values));
	}
}
