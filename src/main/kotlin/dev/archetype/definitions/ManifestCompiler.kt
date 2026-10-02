package dev.archetype.definitions

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Compiles only operations backed by runtime handlers. No accepted YAML is stored as an untyped map. */
class ManifestCompiler(private val catalog: MechanicCatalog = BuiltinEffects.catalog) {
    private val loader = Load(
        LoadSettings.builder()
            .setAllowDuplicateKeys(false)
            .setMaxAliasesForCollections(0)
            .setCodePointLimit(1_048_576)
            .build(),
    )

    fun compile(snapshot: PackSnapshot): CompileResult {
        val errors = mutableListOf<Diagnostic>()
        val documents = mutableListOf<Doc>()
        // ASVS 1.5.2, 15.3.5: YAML becomes scalar/list/map data only; every field is then checked.
        for (file in snapshot.files) {
            try {
                val decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                val value = loader.loadFromString(decoder.decode(ByteBuffer.wrap(file.bytes)).toString())
                documents += Doc(file.relativePath, value.asMap(file.relativePath, "$"))
            } catch (failure: InvalidManifest) {
                errors += Diagnostic(file.relativePath, failure.field, failure.message ?: "invalid manifest")
            } catch (failure: Exception) {
                errors += Diagnostic(file.relativePath, "$", failure.message?.lineSequence()?.firstOrNull() ?: "invalid YAML")
            }
        }
        if (errors.isNotEmpty()) return CompileResult.Invalid(errors)

        val packs = linkedMapOf<String, Pack>()
        val definitions = mutableListOf<Pair<Doc, String>>()
        for (doc in documents) {
            val folder = doc.file.substringBefore('/')
            try {
                if (!doc.file.contains('/')) bad("$", "manifest must be inside a pack folder")
                if (doc.file.substringAfterLast('/') == "pack.yaml" && doc.file.count { it == '/' } == 1) {
                    val p = parsePack(doc)
                    if (p.id != folder) bad("id", "pack ID must match its folder")
                    if (packs.putIfAbsent(p.id, p) != null) bad("id", "duplicate pack ID")
                } else {
                    definitions += doc to folder
                }
            } catch (failure: InvalidManifest) {
                errors += Diagnostic(doc.file, failure.field, failure.message ?: "invalid manifest")
            }
        }
        for (pack in packs.values) {
            for (dependency in pack.dependencies) {
                if (dependency !in packs) errors += Diagnostic("${pack.id}/pack.yaml", "dependencies", "missing pack $dependency")
                if (dependency == pack.id) errors += Diagnostic("${pack.id}/pack.yaml", "dependencies", "pack cannot depend on itself")
            }
        }

        val resources = linkedMapOf<String, ResourceDef>()
        val states = linkedMapOf<String, StateDef>()
        val abilities = linkedMapOf<String, AbilityDef>()
        val classes = linkedMapOf<String, ClassDef>()
        val specializations = linkedMapOf<String, SpecializationDef>()
        val areas = linkedMapOf<String, AreaDef>()
        val statuses = linkedMapOf<String, StatusDef>()
        val projectiles = linkedMapOf<String, ProjectileDef>()
        val blockPatterns = linkedMapOf<String, BlockPatternDef>()
        val progressionTracks = linkedMapOf<String, ProgressionTrackDef>()
        val empowerments = linkedMapOf<String, EmpowermentDef>()
        val unlockTrees = linkedMapOf<String, UnlockTreeDef>()
        val rawClasses = mutableListOf<Pair<Doc, String>>()
        val rawSpecializations = mutableListOf<Pair<Doc, String>>()
        val usedIds = mutableSetOf<String>()
        for ((doc, packId) in definitions) {
            try {
                val pack = packs[packId] ?: bad("$", "pack.yaml is missing")
                val kind = doc.data.string("kind", "$")
                val id = qualify(doc.data.string("id", "$"), pack.id, pack, "id")
                if (!usedIds.add(id)) bad("id", "duplicate definition ID $id")
                when (kind) {
                    "resource" -> resources[id] = parseResource(doc.data, id)
                    "state" -> states[id] = parseState(doc.data, id)
                    "ability" -> abilities[id] = parseAbility(doc.data, id, true, pack)
                    "area" -> areas[id] = parseArea(doc.data, id, pack)
                    "status" -> statuses[id] = parseStatus(doc.data, id, pack)
                    "projectile" -> projectiles[id] = parseProjectile(doc.data, id, pack)
                    "block_pattern" -> blockPatterns[id] = parseBlockPattern(doc.data, id)
                    "progression_track" -> progressionTracks[id] = parseProgressionTrack(doc.data, id, pack)
                    "empowerment" -> empowerments[id] = parseEmpowerment(doc.data, id, pack)
                    "unlock_tree" -> unlockTrees[id] = parseUnlockTree(doc.data, id, pack)
                    "class" -> rawClasses += doc to packId
                    "specialization" -> rawSpecializations += doc to packId
                    else -> bad("kind", "unsupported definition kind $kind")
                }
            } catch (failure: InvalidManifest) {
                errors += Diagnostic(doc.file, failure.field, failure.message ?: "invalid manifest")
            }
        }
        for ((doc, packId) in rawClasses) {
            try {
                val pack = packs.getValue(packId)
                val id = qualify(doc.data.string("id", "$"), packId, pack, "id")
                classes[id] = parseClass(doc.data, id, pack, abilities)
            } catch (failure: InvalidManifest) {
                errors += Diagnostic(doc.file, failure.field, failure.message ?: "invalid manifest")
            }
        }
        for ((doc, packId) in rawSpecializations) {
            try {
                val pack = packs.getValue(packId)
                val id = qualify(doc.data.string("id", "$"), packId, pack, "id")
                val classId = reference(doc.data["class"], pack, "class")
                if (classId !in classes) bad("class", "unknown class $classId")
                doc.data.only(setOf("kind", "id", "name", "class", "abilities"), "$")
                val parsed = parseClass(doc.data.filterKeys { it != "class" }, id, pack, abilities)
                val base = classes.getValue(classId).grants
                for (name in parsed.grants.keys) if (name in base)
                    bad("abilities.$name", "specialization grant conflicts with base class grant")
                specializations[id] = SpecializationDef(id, parsed.name, classId, parsed.grants)
            } catch (failure: InvalidManifest) {
                errors += Diagnostic(doc.file, failure.field, failure.message ?: "invalid manifest")
            }
        }
        if (classes.size > 128) errors += Diagnostic("packs", "classes", "at most 128 classes are supported")
        if (specializations.size > 256) errors += Diagnostic("packs", "specializations", "at most 256 specializations are supported")
        for ((id, spec) in specializations) if (spec.grants.size + classes.getValue(spec.classId).grants.size > 128)
            errors += Diagnostic(id, "abilities", "at most 128 class and specialization grants are supported")
        if (resources.size > 128) errors += Diagnostic("packs", "resources", "at most 128 resources are supported")
        if (states.size > 128) errors += Diagnostic("packs", "states", "at most 128 state definitions are supported")
        if (areas.size > 256) errors += Diagnostic("packs", "areas", "at most 256 areas are supported")
        if (statuses.size > 256) errors += Diagnostic("packs", "statuses", "at most 256 statuses are supported")
        for ((id, status) in statuses) status.state?.let { ref ->
            if (states[ref]?.scope != StateScope.STATUS) errors += Diagnostic(id, "state", "status state $ref must refer to a status-scoped state definition")
        }
        for ((id, status) in statuses) for (replacement in status.replacements) {
            if (replacement.replacement !in abilities)
                errors += Diagnostic(id, "modifiers.replacement", "unknown ability ${replacement.replacement}")
            if (classes.values.none { replacement.grant in it.grants } &&
                specializations.values.none { replacement.grant in it.grants } &&
                unlockTrees.values.none { tree -> tree.nodes.values.any { node -> node.abilities.any { it.grant == replacement.grant } } })
                errors += Diagnostic(id, "modifiers.target.ability", "unknown grant ${replacement.grant}")
            val pack = packs[id.substringBefore(':')]
            if (pack != null && replacement.replacement.substringBefore(':') !in pack.dependencies + pack.id)
                errors += Diagnostic(id, "modifiers.replacement", "undeclared pack dependency")
        }
        data class ReplacementSource(val replacement: AbilityReplacement, val tree: String? = null,
            val group: String? = null, val node: String? = null)
        val replacementSources = statuses.values.flatMap { status -> status.replacements.map { ReplacementSource(it) } } +
            unlockTrees.values.flatMap { tree -> tree.nodes.values.flatMap { node -> node.empowerments.flatMap { id ->
                empowerments[id]?.replacements.orEmpty().map { ReplacementSource(it, tree.id,
                    if (node.selection == UnlockSelection.TALENT) node.choiceGroup else null, node.id) }
            } } }
        val replacementConflicts = replacementSources.groupBy { it.replacement.grant to it.replacement.priority }
        for ((target, sources) in replacementConflicts) {
            val conflict = sources.indices.any { left -> (left + 1 until sources.size).any { right ->
                val a = sources[left]
                val b = sources[right]
                a.replacement.replacement != b.replacement.replacement &&
                    !(a.tree != null && a.tree == b.tree && a.group != null && a.group == b.group && a.node != b.node)
            } }
            if (conflict) errors += Diagnostic("packs", "modifiers", "ambiguous replacement for ${target.first} at priority ${target.second}")
        }
        if (projectiles.size > 256) errors += Diagnostic("packs", "projectiles", "at most 256 projectiles are supported")
        if (blockPatterns.size > 256) errors += Diagnostic("packs", "block_patterns", "at most 256 block patterns are supported")
        if (progressionTracks.size > 128) errors += Diagnostic("packs", "progression_tracks", "at most 128 progression tracks are supported")
        for ((id, track) in progressionTracks) for (classId in track.classes)
            if (classId !in classes) errors += Diagnostic(id, "classes", "unknown class $classId")
        if (empowerments.size > 256) errors += Diagnostic("packs", "empowerments", "at most 256 empowerments are supported")
        if (unlockTrees.size > 128) errors += Diagnostic("packs", "unlock_trees", "at most 128 unlock trees are supported")
        for ((id, tree) in unlockTrees) {
            if (tree.track !in progressionTracks) errors += Diagnostic(id, "track", "unknown progression track ${tree.track}")
            for (specialization in tree.specializations) {
                val definition = specializations[specialization]
                if (definition == null) errors += Diagnostic(id, "specializations", "unknown specialization $specialization")
                else if (progressionTracks[tree.track]?.let { track -> track.scope != ProgressScope.CLASS ||
                        (if (track.classes.isEmpty()) definition.classId.substringBefore(':') != track.id.substringBefore(':')
                        else definition.classId !in track.classes) } == true)
                    errors += Diagnostic(id, "specializations", "specialization $specialization cannot use this track")
            }
            if (tree.specializations.isNotEmpty() && progressionTracks[tree.track]?.scope == ProgressScope.PLAYER)
                errors += Diagnostic(id, "specializations", "specialization trees require a class-scoped track")
            for (node in tree.nodes.values) for (empowerment in node.empowerments)
                if (empowerment !in empowerments) errors += Diagnostic(id, "nodes.${node.id}.grants", "unknown empowerment $empowerment")
        }
        data class UnlockClaim(val source: String, val specializations: Set<String>)
        val unlockedByClass = mutableMapOf<String, MutableMap<String, MutableList<UnlockClaim>>>()
        for ((id, tree) in unlockTrees) {
            val track = progressionTracks[tree.track] ?: continue
            val eligibleClasses = if (tree.specializations.isNotEmpty()) tree.specializations.mapNotNull { specializations[it]?.classId }.distinct()
                else if (track.scope != ProgressScope.CLASS) classes.keys
                else if (track.classes.isNotEmpty()) track.classes
                else classes.keys.filter { it.substringBefore(':') == track.id.substringBefore(':') }
            for (node in tree.nodes.values) for (unlock in node.abilities) {
                if (unlock.ability !in abilities)
                    errors += Diagnostic(id, "nodes.${node.id}.grants", "unknown ability ${unlock.ability}")
                for (classId in eligibleClasses) {
                    val classDef = classes[classId] ?: continue
                    if (unlock.grant in classDef.grants || specializations.values.any {
                            it.classId == classId && unlock.grant in it.grants &&
                                (tree.specializations.isEmpty() || it.id in tree.specializations)
                        })
                        errors += Diagnostic(id, "nodes.${node.id}.grants", "grant ${unlock.grant} already exists in $classId")
                    val claims = unlockedByClass.getOrPut(classId) { mutableMapOf() }
                        .getOrPut(unlock.grant) { mutableListOf() }
                    val previous = claims.firstOrNull { existing ->
                        existing.specializations.isEmpty() || tree.specializations.isEmpty() ||
                            existing.specializations.any { it in tree.specializations }
                    }
                    if (previous != null)
                        errors += Diagnostic(id, "nodes.${node.id}.grants", "grant ${unlock.grant} conflicts with ${previous.source} in $classId")
                    claims += UnlockClaim("$id/${node.id}", tree.specializations)
                }
            }
        }
        for ((classId, names) in unlockedByClass)
            if (names.size + (classes[classId]?.grants?.size ?: 0) > 128)
                errors += Diagnostic(classId, "abilities", "at most 128 base and unlocked grants are supported")
        for ((id, empowerment) in empowerments) for (replacement in empowerment.replacements) {
            if (replacement.replacement !in abilities) errors += Diagnostic(id, "changes.replacement", "unknown ability ${replacement.replacement}")
            if (classes.values.none { replacement.grant in it.grants } &&
                specializations.values.none { replacement.grant in it.grants } &&
                unlockTrees.values.none { tree -> tree.nodes.values.any { node -> node.abilities.any { it.grant == replacement.grant } } })
                errors += Diagnostic(id, "changes.target.ability", "unknown grant ${replacement.grant}")
        }
        // ASVS 2.2.3: one attribute must have one unambiguous composition policy across packs.
        if (statuses.values.mapNotNull { it.speed }.map { it.combination to it.cap }.distinct().size > 1)
            errors += Diagnostic("packs", "statuses.modifiers", "movement speed modifiers must use the same stacking policy and cap")
        val authoredGrants = classes.values.flatMap { it.grants.values } + specializations.values.flatMap { it.grants.values }
        for ((id, ability) in abilities + authoredGrants.associate { it.ability.id to it.ability }) {
            val pack = packs[id.substringBefore(':')] ?: continue
            for (cost in ability.costs) {
                if (cost.resource !in resources) errors += Diagnostic(id, "costs", "unknown resource ${cost.resource}")
                else if (cost.resource.substringBefore(':') !in pack.dependencies + pack.id) errors += Diagnostic(id, "costs", "undeclared pack dependency")
            }
            for (cost in ability.periodicCosts) {
                if (cost.resource !in resources) errors += Diagnostic(id, "activation.periodic_costs", "unknown resource ${cost.resource}")
                else if (cost.resource.substringBefore(':') !in pack.dependencies + pack.id)
                    errors += Diagnostic(id, "activation.periodic_costs", "undeclared pack dependency")
            }
        }
        val bodies = abilities.mapValues { it.value.effects + it.value.recastEffects } + authoredGrants.associate { it.ability.id to it.ability.effects + it.ability.recastEffects } +
            areas.mapValues { (_, area) -> area.enter + area.periodic + area.exit + area.expired } + statuses.mapValues { it.value.bodies } +
            projectiles.mapValues { it.value.bodies }
        val declaredGroups = (abilities.values + authoredGrants.map(Grant::ability)).flatMap { it.cooldownGroups }.toSet()
        for ((id, effects) in bodies) for (effect in effects.flatMap { catalog.descendants(it).toList() }) {
            if (effect is Effect.PlacePattern && effect.pattern !in blockPatterns)
                errors += Diagnostic(id, "effects.pattern", "unknown block pattern ${effect.pattern}")
            if (effect is Effect.ReduceGroupCooldown && effect.group !in declaredGroups)
                errors += Diagnostic(id, "effects.group", "unknown cooldown group ${effect.group}")
            if (effect is Effect.Dash && effect.distance is Numeric.Constant && effect.distance.value !in 0.01..32.0)
                errors += Diagnostic(id, "effects.distance", "dash distance must be 0.01..32 blocks")
            if (effect is Effect.Impulse && effect.distance is Numeric.Constant && effect.distance.value !in 0.01..32.0)
                errors += Diagnostic(id, "effects.distance", "impulse distance must be 0.01..32 blocks")
        }
        val grantContexts = classes.map { (id, definition) -> id to definition.grants } +
            specializations.values.map { spec -> spec.id to (classes.getValue(spec.classId).grants + spec.grants) }
        for ((classId, grants) in grantContexts) for ((grantName, grant) in grants) {
            // ASVS 2.2.2, 2.2.3: resolve named grant edits against each actual granting class, including controller callbacks.
            val visited = mutableSetOf<String>()
            val reported = mutableSetOf<String>()
            fun visit(effects: List<Effect>) {
                for (effect in effects.flatMap { catalog.descendants(it).toList() }) {
                    val mechanic = catalog.mechanic(effect)
                    for (ref in mechanic.grants(effect)) if (ref != "self" && ref !in grants && reported.add(ref))
                        errors += Diagnostic("$classId/$grantName", "effects.grant", "unknown logical grant $ref")
                    for (ref in mechanic.areas(effect)) if (visited.add("area:$ref")) areas[ref]?.let { area ->
                        visit(area.enter + area.periodic + area.exit + area.expired)
                        area.buffs.forEach { buff -> if (visited.add("status:$buff")) statuses[buff]?.let { visit(it.bodies) } }
                    }
                    for (ref in mechanic.projectiles(effect)) if (visited.add("projectile:$ref")) projectiles[ref]?.let { visit(it.bodies) }
                    for (ref in mechanic.createdStatuses(effect)) if (visited.add("status:$ref")) statuses[ref]?.let { visit(it.bodies) }
                }
            }
            visit(grant.ability.effects + grant.ability.recastEffects)
        }
        for ((id, area) in areas) for (status in area.buffs) if (status !in statuses) errors += Diagnostic(id, "buffs", "unknown status $status")
        for ((id, effects) in bodies) for (effect in effects.flatMap { catalog.descendants(it).toList() }) {
            val mechanic = catalog.mechanic(effect)
            for (numeric in mechanic.inputs(effect).values.filterIsInstance<Numeric.Expression>())
                for (variable in Expression.variables(numeric.source).filter { it.startsWith("state.") }) {
                    val segments = variable.split('.')
                    val stateId = "${segments[1]}:${segments[2]}"
                    val field = states[stateId]?.fields?.get(segments[3])
                    if (field == null) errors += Diagnostic(id, "effects", "unknown state variable $variable")
                    else if (field.type == StateType.ENUM) errors += Diagnostic(id, "effects", "$variable is not numeric")
                    else if (states[stateId]?.scope == StateScope.STATUS && statuses[id]?.state != stateId)
                        errors += Diagnostic(id, "effects", "$variable is only available in its status instance")
                }
            for (resource in mechanic.resources(effect)) if (resource !in resources) errors += Diagnostic(id, "effects", "unknown resource $resource")
            for (state in mechanic.states(effect)) {
                val definition = states[state]
                if (definition == null) {
                    errors += Diagnostic(id, "effects.state", "unknown state $state")
                    continue
                }
                if (definition.scope == StateScope.STATUS && effect !is Effect.ReadStatusState && effect !is Effect.WriteStatusState && statuses[id]?.state != state)
                    errors += Diagnostic(id, "effects.state", "status state $state is only available in its status instance")
                val fieldName = when (effect) {
                    is Effect.SetState -> effect.field
                    is Effect.AddState -> effect.field
                    is Effect.ResetState -> effect.field
                    is Effect.ReadState -> effect.field
                    is Effect.ReadStatusState -> effect.field
                    is Effect.WriteStatusState -> effect.field
                    else -> continue
                }
                val field = definition.fields[fieldName]
                if (field == null) {
                    errors += Diagnostic(id, "effects.field", "unknown state field $state.$fieldName")
                    continue
                }
                when (effect) {
                    is Effect.SetState -> if (when (effect.value) {
                        is StateInput.Number -> (field.type != StateType.NUMBER && field.type != StateType.INTEGER) ||
                            ((effect.value.value as? Numeric.Constant)?.let { field.type == StateType.INTEGER && it.value % 1.0 != 0.0 } == true)
                        is StateInput.Flag -> field.type != StateType.BOOLEAN
                        is StateInput.Mode -> field.type != StateType.ENUM || effect.value.value !in field.choices
                    }) errors += Diagnostic(id, "effects.value", "value does not match $state.$fieldName")
                    is Effect.AddState -> if (field.type != StateType.NUMBER && field.type != StateType.INTEGER)
                        errors += Diagnostic(id, "effects.amount", "add_state requires a numeric field")
                    is Effect.ReadState -> if (field.type == StateType.ENUM)
                        errors += Diagnostic(id, "effects.field", "read_state requires a numeric or Boolean field")
                    is Effect.ReadStatusState -> {
                        if (definition.scope != StateScope.STATUS || field.type == StateType.ENUM)
                            errors += Diagnostic(id, "effects.field", "read_status_state requires a numeric or Boolean status field")
                        if (statuses.values.none { it.state == state })
                            errors += Diagnostic(id, "effects.state", "no status attaches state $state")
                        effect.filter.status?.let { filter -> if (statuses[filter]?.state != state)
                            errors += Diagnostic(id, "effects.status", "status $filter does not attach state $state") }
                    }
                    is Effect.WriteStatusState -> {
                        if (definition.scope != StateScope.STATUS)
                            errors += Diagnostic(id, "effects.state", "write_status_state requires status state")
                        if (statuses.values.none { it.state == state })
                            errors += Diagnostic(id, "effects.state", "no status attaches state $state")
                        effect.filter.status?.let { filter -> if (statuses[filter]?.state != state)
                            errors += Diagnostic(id, "effects.status", "status $filter does not attach state $state") }
                        when (effect.operation) {
                            StatusStateOperation.SET -> if (when (val value = effect.value) {
                                is StateInput.Number -> (field.type != StateType.NUMBER && field.type != StateType.INTEGER) ||
                                    ((value.value as? Numeric.Constant)?.let { field.type == StateType.INTEGER && it.value % 1.0 != 0.0 } == true)
                                is StateInput.Flag -> field.type != StateType.BOOLEAN
                                is StateInput.Mode -> field.type != StateType.ENUM || value.value !in field.choices
                                null -> true
                            }) errors += Diagnostic(id, "effects.value", "value does not match $state.$fieldName")
                            StatusStateOperation.ADD -> if (field.type != StateType.NUMBER && field.type != StateType.INTEGER)
                                errors += Diagnostic(id, "effects.amount", "add requires a numeric status field")
                            StatusStateOperation.RESET -> Unit
                        }
                    }
                    else -> Unit
                }
            }
            val stateConditions = when (effect) {
                is Effect.Branch -> effect.condition.leaves().toList()
                is Effect.Parallel -> effect.branches.flatMap { it.successWhen?.leaves()?.toList().orEmpty() }
                else -> emptyList()
            }
            for (condition in stateConditions.filterIsInstance<Condition.StateIs>()) {
                val field = states[condition.state]?.fields?.get(condition.field)
                if (field == null) errors += Diagnostic(id, "effects.when", "unknown state field ${condition.state}.${condition.field}")
                else if (!stateLiteralMatches(condition.value, field))
                    errors += Diagnostic(id, "effects.when.value", "value does not match ${condition.state}.${condition.field}")
            }
            for (area in mechanic.areas(effect)) if (area !in areas) errors += Diagnostic(id, "effects", "unknown area $area")
            for (projectile in mechanic.projectiles(effect)) if (projectile !in projectiles) errors += Diagnostic(id, "effects", "unknown projectile $projectile")
            if (effect is Effect.LaunchProjectile && projectiles[effect.projectile]?.homingDegreesPerTick != null && effect.direction != ProjectileDirection.TARGET)
                errors += Diagnostic(id, "effects.direction", "homing projectile requires actor.to_target direction")
            if (effect is Effect.LaunchProjectile) projectiles[effect.projectile]?.let { projectile ->
                for ((name, value) in effect.arguments) {
                    val parameter = projectile.parameters[name]
                    if (parameter == null) errors += Diagnostic(id, "effects.projectile.with.$name", "unknown projectile parameter $name")
                    else if (value is Numeric.Constant && value.value !in parameter.minimum..parameter.maximum)
                        errors += Diagnostic(id, "effects.projectile.with.$name", "parameter value is outside its declared bounds")
                }
                for ((name, parameter) in projectile.parameters) if (parameter.default == null && name !in effect.arguments)
                    errors += Diagnostic(id, "effects.projectile.with.$name", "required projectile parameter is missing")
            }
            for (status in mechanic.statuses(effect)) if (status !in statuses) errors += Diagnostic(id, "effects", "unknown status $status")
        }
        for ((id, effects) in bodies) {
            val applications = effects.flatMap { catalog.descendants(it).toList() }.filterIsInstance<Effect.ApplyStatus>()
            if (applications.map { it.applicationId }.distinct().size != applications.size) errors += Diagnostic(id, "effects.id", "duplicate status application identity")
        }
        val visited = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()
        fun visitController(id: String) {
            if (id in visited || (id !in areas && id !in statuses && id !in projectiles)) return
            if (!visiting.add(id)) {
                errors += Diagnostic(id, "effects", "recursive area, status, or projectile creation is not supported")
                return
            }
            for (ref in areas[id]?.buffs.orEmpty()) visitController(ref)
            for (effect in bodies.getValue(id).flatMap { catalog.descendants(it).toList() }) {
                val mechanic = catalog.mechanic(effect)
                for (ref in mechanic.areas(effect) + mechanic.createdStatuses(effect) + mechanic.projectiles(effect)) visitController(ref)
            }
            visiting -= id
            visited += id
        }
        (areas.keys + statuses.keys + projectiles.keys).forEach(::visitController)
        if (errors.isNotEmpty()) return CompileResult.Invalid(errors)
        return CompileResult.Valid(DefinitionSet(packs, resources, abilities, classes, snapshot.fingerprint, areas, statuses, states, projectiles, progressionTracks, empowerments, unlockTrees, specializations, blockPatterns))
    }

    private fun parseBlockPattern(m: Map<String, Any?>, id: String): BlockPatternDef {
        m.only(setOf("kind", "id", "origin", "palette", "layers"), "$")
        val origin = (m["origin"] as? List<*>) ?: bad("origin", "expected [x, y, z]")
        if (origin.size != 3 || origin.any { it !is Int && it !is Long } || origin.any { (it as Number).toLong() !in 0L..15L })
            bad("origin", "expected three cell indices from 0 to 15")
        val offset = origin.map { (it as Number).toInt() }
        val palette = m["palette"].asMap("palette", "palette")
        if (palette.isEmpty() || palette.size > 32) bad("palette", "expected 1..32 symbols")
        val symbols = palette.mapValues { (symbol, raw) ->
            if (symbol.length != 1) bad("palette.$symbol", "symbol must be one character")
            val entry = raw.asMap("palette.$symbol", "palette.$symbol")
            entry.only(setOf("block", "skip"), "palette.$symbol")
            if (("block" in entry) == ("skip" in entry)) bad("palette.$symbol", "expected exactly one block or skip")
            if ("skip" in entry) {
                if (entry["skip"] != true) bad("palette.$symbol.skip", "skip must be true")
                null
            } else entry.string("block", "palette.$symbol").also {
                if (it.length > 256 || !Regex("[a-z0-9_.-]+:[a-z0-9_./-]+(?:\\[[a-z0-9_=,.-]+])?").matches(it))
                    bad("palette.$symbol.block", "invalid block-state syntax")
            }
        }
        val layers = m["layers"] as? List<*> ?: bad("layers", "expected a list of layers")
        if (layers.size !in 1..16) bad("layers", "expected 1..16 layers")
        var width = -1
        var depth = -1
        val cells = mutableListOf<PatternCell>()
        for ((y, rawLayer) in layers.withIndex()) {
            val rows = rawLayer as? List<*> ?: bad("layers.$y", "expected a list of rows")
            if (rows.size !in 1..16 || (depth != -1 && rows.size != depth)) bad("layers.$y", "layers must have 1..16 matching rows")
            depth = rows.size
            for ((z, rawRow) in rows.withIndex()) {
                val row = rawRow as? String ?: bad("layers.$y.$z", "expected a symbol row")
                if (row.length !in 1..16 || (width != -1 && row.length != width)) bad("layers.$y.$z", "rows must have 1..16 matching symbols")
                width = row.length
                for ((x, symbol) in row.withIndex()) {
                    if (symbol.toString() !in symbols) bad("layers.$y.$z", "undeclared palette symbol $symbol")
                    symbols[symbol.toString()]?.let { cells += PatternCell(x - offset[0], y - offset[1], z - offset[2], it) }
                }
            }
        }
        if (offset[0] >= width || offset[1] >= layers.size || offset[2] >= depth)
            bad("origin", "origin must lie within the pattern")
        if (cells.isEmpty() || cells.size > 256) bad("layers", "expected 1..256 edited cells")
        return BlockPatternDef(id, cells)
    }

    private fun parsePack(doc: Doc): Pack {
        val m = doc.data
        m.only(setOf("format", "id", "name", "dependencies"), "$")
        if (m.integer("format", "$") != 1) bad("format", "supported format is 1")
        val id = m.string("id", "$")
        if (id.length > 64 || !PACK_ID.matches(id)) bad("id", "invalid pack ID")
        val rawDependencies = m.listOrEmpty("dependencies", "$")
        if (rawDependencies.size > 128) bad("dependencies", "at most 128 dependencies are supported")
        val dependencies = rawDependencies.mapIndexed { index, value ->
            val dependency = value.asString("dependencies[$index]")
            if (dependency.length > 64 || !PACK_ID.matches(dependency)) bad("dependencies[$index]", "invalid pack ID")
            dependency
        }.toSet()
        return Pack(id, m.string("name", "$"), dependencies)
    }

    private fun parseResource(m: Map<String, Any?>, id: String): ResourceDef {
        m.only(setOf("kind", "id", "scope", "min", "max", "initial", "regeneration"), "$")
        val scope = when (m.string("scope", "$")) {
            "class" -> ResourceScope.CLASS
            "player" -> ResourceScope.PLAYER
            else -> bad("scope", "supported scopes are class and player")
        }
        val min = m.number("min", "$" )
        val max = m.number("max", "$" )
        val initial = m.number("initial", "$" )
        if (min < -1_000_000_000 || max > 1_000_000_000 || min > max || initial !in min..max) bad("initial", "resource bounds or initial value are invalid")
        val regeneration = m["regeneration"]?.asMap("regeneration", "regeneration")?.let { regen ->
            regen.only(setOf("amount", "every"), "regeneration")
            val amount = regen.number("amount", "regeneration")
            if (amount <= 0 || amount > 1_000_000) bad("regeneration.amount", "must be positive and at most 1000000")
            Regeneration(amount, ticks(regen.string("every", "regeneration"), "regeneration.every"))
        }
        return ResourceDef(id, scope, min, max, initial, regeneration)
    }

    private fun parseState(m: Map<String, Any?>, id: String): StateDef {
        m.only(setOf("kind", "id", "scope", "persistent", "fields"), "$")
        val scope = when (m.string("scope", "$")) {
            "player" -> StateScope.PLAYER
            "class" -> StateScope.CLASS
            "activation" -> StateScope.ACTIVATION
            "status" -> StateScope.STATUS
            else -> bad("scope", "supported state scopes are player, class, activation, and status")
        }
        val persistent = m["persistent"]?.let { it as? Boolean ?: bad("persistent", "expected a Boolean") } ?: false
        if (persistent && scope in setOf(StateScope.ACTIVATION, StateScope.STATUS)) bad("persistent", "activation and status state cannot persist")
        val raw = m["fields"].asMap("fields", "fields")
        if (raw.isEmpty() || raw.size > 64) bad("fields", "state must have 1..64 fields")
        val fields = linkedMapOf<String, StateField>()
        for ((name, value) in raw) {
            val path = "fields.$name"
            if (name.length > 64 || !LOCAL_ID.matches(name)) bad(path, "invalid state field name")
            val item = value.asMap(path, path)
            val type = when (item.string("type", path)) {
                "boolean" -> StateType.BOOLEAN
                "enum" -> StateType.ENUM
                "integer" -> StateType.INTEGER
                "number" -> StateType.NUMBER
                else -> bad("$path.type", "supported state types are boolean, enum, integer, and number")
            }
            val field = when (type) {
                StateType.BOOLEAN -> {
                    item.only(setOf("type", "initial"), path)
                    StateField(type, StateValue.Flag(item["initial"] as? Boolean ?: bad("$path.initial", "expected a Boolean")))
                }
                StateType.ENUM -> {
                    item.only(setOf("type", "values", "initial"), path)
                    val options = item.listOrEmpty("values", path).mapIndexed { index, choice ->
                        choice.asString("$path.values[$index]").also { if (it.length > 64 || !LOCAL_ID.matches(it)) bad("$path.values[$index]", "invalid mode") }
                    }
                    if (options.isEmpty() || options.size > 32 || options.distinct().size != options.size) bad("$path.values", "expected 1..32 distinct modes")
                    val initial = item.string("initial", path)
                    if (initial !in options) bad("$path.initial", "initial mode is not declared")
                    StateField(type, StateValue.Mode(initial), choices = options.toSet())
                }
                StateType.INTEGER, StateType.NUMBER -> {
                    item.only(setOf("type", "min", "max", "initial"), path)
                    val minimum = item.number("min", path)
                    val maximum = item.number("max", path)
                    val initial = item.number("initial", path)
                    if (minimum < -1_000_000_000 || maximum > 1_000_000_000 || minimum > maximum || initial !in minimum..maximum ||
                        (type == StateType.INTEGER && listOf(minimum, maximum, initial).any { it % 1.0 != 0.0 }))
                        bad(path, "numeric state bounds or initial value are invalid")
                    StateField(type, StateValue.Number(initial), minimum, maximum)
                }
            }
            fields[name] = field
        }
        return StateDef(id, scope, persistent, fields)
    }

    private fun stateLiteralMatches(value: StateValue, field: StateField): Boolean = when (value) {
        is StateValue.Flag -> field.type == StateType.BOOLEAN
        is StateValue.Mode -> field.type == StateType.ENUM && value.value in field.choices
        is StateValue.Number -> (field.type == StateType.NUMBER || field.type == StateType.INTEGER) &&
            value.value in field.minimum!!..field.maximum!! && (field.type != StateType.INTEGER || value.value % 1.0 == 0.0)
    }

    private fun parseAbility(m: Map<String, Any?>, id: String, global: Boolean, pack: Pack): AbilityDef {
        val allowed = setOf("name", "description", "activation", "cooldown", "charges", "costs", "effects", "recast_effects", "icon", "target") + if (global) setOf("kind", "id") else emptySet()
        m.only(allowed, "$" )
        for (field in listOf("description", "icon")) if (field in m) m.string(field, "$")
        var channelEvery = 0
        var channelMax: Int? = null
        var periodicCosts: List<Cost> = emptyList()
        var chargeMin = 0
        var chargeMax = 0
        var confirmWindow = 0
        var recastWindow = 0
        val activation = when (val mode = m["activation"]) {
            null -> Activation.ACTIVATED
            is Map<*, *> -> {
                val settings = mode.asMap("activation", "activation")
                val type = settings.string("type", "activation")
                settings.only(when (type) {
                    "channel" -> setOf("type", "every", "max_duration", "periodic_costs")
                    "charge" -> setOf("type", "min_hold", "max_hold")
                    "confirm" -> setOf("type", "window")
                    "recast" -> setOf("type", "window")
                    else -> setOf("type")
                }, "activation")
                when (type) {
                    "activated" -> Activation.ACTIVATED
                    "passive" -> Activation.PASSIVE
                    "toggle" -> Activation.TOGGLE
                    "channel" -> {
                        channelEvery = ticks(settings.string("every", "activation"), "activation.every")
                        if (channelEvery <= 0) bad("activation.every", "channel interval must be positive")
                        channelMax = settings["max_duration"]?.asString("activation.max_duration")?.let { ticks(it, "activation.max_duration") }
                        if (channelMax != null && channelMax <= 0) bad("activation.max_duration", "maximum duration must be positive")
                        val rawPeriodic = settings.listOrEmpty("periodic_costs", "activation")
                        if (rawPeriodic.size > 128) bad("activation.periodic_costs", "at most 128 periodic costs are supported")
                        periodicCosts = rawPeriodic.mapIndexed { index, value ->
                            val field = "activation.periodic_costs[$index]"
                            val cost = value.asMap(field, field)
                            cost.only(setOf("resource", "amount"), field)
                            Cost(qualify(cost.string("resource", field), pack.id, pack, "$field.resource"),
                                numeric(cost["amount"], "$field.amount").also { checkNumeric(it, emptySet(), "$field.amount", allowState = false) })
                        }
                        Activation.CHANNEL
                    }
                    "charge" -> {
                        chargeMin = settings["min_hold"]?.asString("activation.min_hold")?.let { ticks(it, "activation.min_hold") } ?: 0
                        chargeMax = ticks(settings.string("max_hold", "activation"), "activation.max_hold")
                        if (chargeMax <= 0 || chargeMin !in 0..chargeMax)
                            bad("activation.max_hold", "charge maximum must be positive and at least min_hold")
                        Activation.CHARGE
                    }
                    "confirm" -> {
                        confirmWindow = ticks(settings.string("window", "activation"), "activation.window")
                        if (confirmWindow !in 1..1200) bad("activation.window", "confirmation window must be 1..1200 ticks")
                        Activation.CONFIRM
                    }
                    "recast" -> {
                        recastWindow = ticks(settings.string("window", "activation"), "activation.window")
                        if (recastWindow !in 1..1200) bad("activation.window", "recast window must be 1..1200 ticks")
                        Activation.RECAST
                    }
                    else -> bad("activation.type", "unsupported activation mode")
                }
            }
            else -> bad("activation", "expected a mapping")
        }
        var cooldown = 0
        var cooldownGroups: Set<String> = emptySet()
        var globalCooldown = 0
        // ASVS 2.2.1, 2.2.3: timing modes and group combinations are checked before publication.
        when (val timing = m["cooldown"]) {
            null -> Unit
            is String -> cooldown = ticks(timing, "cooldown")
            is Map<*, *> -> {
                val settings = timing.asMap("cooldown", "cooldown")
                settings.only(setOf("duration", "groups", "global"), "cooldown")
                cooldown = ticks(settings.string("duration", "cooldown"), "cooldown.duration")
                val raw = settings.listOrEmpty("groups", "cooldown")
                if (raw.size > 8) bad("cooldown.groups", "at most 8 shared cooldown groups are supported")
                val groups = raw.mapIndexed { index, value -> qualify(value.asString("cooldown.groups[$index]"), pack.id, pack, "cooldown.groups[$index]") }
                if (groups.distinct().size != groups.size) bad("cooldown.groups", "duplicate cooldown group")
                cooldownGroups = groups.toSet()
                globalCooldown = settings["global"]?.asString("cooldown.global")?.let { ticks(it, "cooldown.global") } ?: 0
                if ((cooldownGroups.isNotEmpty() || globalCooldown > 0) && cooldown <= 0) bad("cooldown.duration", "shared cooldown requires a positive ability duration")
            }
            else -> bad("cooldown", "expected a duration or timing mapping")
        }
        val charges = m["charges"]?.asMap("charges", "charges")?.let { settings ->
            // ASVS 2.2.1, 2.3.2: finite capacity and positive recharge bound future work.
            settings.only(setOf("max", "recharge", "mode"), "charges")
            val maximum = settings.integer("max", "charges")
            if (maximum !in 1..16) bad("charges.max", "charge capacity must be 1..16")
            val recharge = ticks(settings.string("recharge", "charges"), "charges.recharge")
            if (recharge <= 0) bad("charges.recharge", "recharge interval must be positive")
            val mode = when (settings["mode"] ?: "sequential") {
                "sequential" -> RechargeMode.SEQUENTIAL
                "parallel" -> RechargeMode.PARALLEL
                else -> bad("charges.mode", "supported recharge modes are sequential and parallel")
            }
            ChargeDef(maximum, recharge, mode)
        }
        val rawCosts = m.listOrEmpty("costs", "$")
        if (rawCosts.size > 128) bad("costs", "at most 128 costs are supported")
        val costs = rawCosts.mapIndexed { index, value ->
            val field = "costs[$index]"
            val cost = value.asMap(field, field)
            cost.only(setOf("resource", "amount"), field)
            val resource = qualify(cost.string("resource", field), pack.id, pack, "$field.resource")
            Cost(resource, numeric(cost["amount"], "$field.amount").also { checkNumeric(it, emptySet(), "$field.amount", allowState = false) })
        }
        if (activation == Activation.PASSIVE) {
            if ("cooldown" in m) bad("cooldown", "passive abilities do not start cooldowns")
            if (charges != null) bad("charges", "passive abilities do not spend charges")
            if (costs.isNotEmpty()) bad("costs", "passive abilities do not commit activation costs")
            if ("target" in m) bad("target", "passive abilities do not take activation targets")
        }
        val effectNames = mutableSetOf<String>()
        val rawEffects = m.listOrEmpty("effects", "$")
        if (rawEffects.size > 64) bad("effects", "effect list exceeds 64 steps")
        val effects = rawEffects.mapIndexed { index, value ->
            val field = "effects[$index]"
            val effect = parseEffect(value.asMap(field, field), field, pack, 0)
            effect.resultName?.let { if (!effectNames.add(it)) bad("$field.as", "duplicate result name") }
            effect
        }
        if (effects.isEmpty()) bad("effects", "ability must have an effect")
        validateBindings(effects, if (activation == Activation.CHARGE) setOf("charge.held_ticks", "charge.fraction") else emptySet(), "effects")
        val recastRaw = m.listOrEmpty("recast_effects", "$")
        if (activation != Activation.RECAST && "recast_effects" in m) bad("recast_effects", "recast effects require recast activation")
        if (activation == Activation.RECAST && recastRaw.isEmpty()) bad("recast_effects", "recast activation needs a second-press body")
        if (recastRaw.size > 64) bad("recast_effects", "recast effect list exceeds 64 steps")
        val recastEffects = recastRaw.mapIndexed { index, value ->
            val field = "recast_effects[$index]"
            parseEffect(value.asMap(field, field), field, pack, 0)
        }
        validateBindings(recastEffects, emptySet(), "recast_effects")
        val targeting = m["target"]?.asMap("target", "target")?.let { target ->
            target.only(setOf("type", "range"), "target")
            val type = when (target.string("type", "target")) {
                "entity" -> Targeting.Type.ENTITY
                "ground" -> Targeting.Type.GROUND
                else -> bad("target.type", "supported targeting modes are entity and ground")
            }
            Targeting(type, boundedNumber(target, "range", "target", 0.01, 32.0, 32.0))
        } ?: Targeting()
        if (targeting.type != Targeting.Type.GROUND && effects.any { requiresGround(it) }) bad("target", "ground positions require target.type: ground")
        if (targeting.type != Targeting.Type.GROUND && recastEffects.any { requiresGround(it) }) bad("target", "ground positions require target.type: ground")
        validateContexts(effects, activation != Activation.PASSIVE && targeting.type == Targeting.Type.ENTITY,
            activation != Activation.PASSIVE && targeting.type == Targeting.Type.GROUND, "effects")
        validateContexts(recastEffects, targeting.type == Targeting.Type.ENTITY,
            targeting.type == Targeting.Type.GROUND, "recast_effects")
        return AbilityDef(id, m.string("name", "$"), activation, cooldown, costs, effects, targeting, charges, cooldownGroups,
            globalCooldown, channelEvery, channelMax, periodicCosts, chargeMin, chargeMax, confirmWindow, recastWindow, recastEffects)
    }

    private fun requiresGround(effect: Effect): Boolean = catalog.mechanic(effect).let { mechanic ->
        mechanic.needsGround(effect) || mechanic.nested(effect).any { body -> body.any { requiresGround(it) } }
    }

    private fun parseArea(m: Map<String, Any?>, id: String, pack: Pack): AreaDef {
        m.only(setOf("kind", "id", "shape", "duration", "lifetime", "sample_every", "targets", "enter", "periodic", "exit", "expired", "buffs"), "$")
        val shape = parseShape(m["shape"].asMap("shape", "shape"), "shape")
        if (("duration" in m) == ("lifetime" in m)) bad("duration", "area needs exactly one of duration or lifetime: maintained")
        val duration = m["duration"]?.asString("duration")?.let { ticks(it, "duration") }
        if ("lifetime" in m && m.string("lifetime", "$") != "maintained") bad("lifetime", "supported lifetime is maintained")
        val sample = m["sample_every"]?.asString("sample_every")?.let { ticks(it, "sample_every") } ?: 2
        if (duration != null && duration <= 0) bad("duration", "area lifetime must be positive")
        if (sample <= 0) bad("sample_every", "sampling interval must be positive")
        val selector = parseSelector(m["targets"].asMap("targets", "targets"), "targets", shape)
        val enter = nestedEffects(m, "enter", "$", pack, 0, false)
        val exit = nestedEffects(m, "exit", "$", pack, 0, false)
        val expired = nestedEffects(m, "expired", "$", pack, 0, false)
        var interval = 0
        val periodic = m["periodic"]?.asMap("periodic", "periodic")?.let { pulse ->
            pulse.only(setOf("every", "effects"), "periodic")
            interval = ticks(pulse.string("every", "periodic"), "periodic.every")
            if (interval <= 0) bad("periodic.every", "periodic interval must be positive")
            nestedEffects(pulse, "effects", "periodic", pack, 0)
        }.orEmpty()
        for ((field, body) in listOf("enter" to enter, "exit" to exit, "expired" to expired, "periodic.effects" to periodic)) {
            validateBindings(body, emptySet(), field)
            validateContexts(body, field != "expired", true, field)
        }
        val buffs = m.listOrEmpty("buffs", "$")
        if (buffs.size > 16) bad("buffs", "at most 16 membership buffs are supported")
        val references = buffs.mapIndexed { index, value -> reference(value, pack, "buffs[$index]") }
        if (references.distinct().size != references.size) bad("buffs", "duplicate membership buff")
        return AreaDef(id, shape, duration, sample, selector, enter, periodic, interval, exit, expired, references)
    }

    private fun parseProjectile(m: Map<String, Any?>, id: String, pack: Pack): ProjectileDef {
        m.only(setOf("kind", "id", "parameters", "speed", "gravity", "lifetime", "collision", "entity_hit", "block_hit", "expiry", "pierce", "bounce", "repeat_hit", "homing"), "$")
        val speed = boundedNumber(m, "speed", "$", 0.1, 64.0)
        val gravity = boundedNumber(m, "gravity", "$", 0.0, 64.0, 0.0)
        val lifetime = ticks(m.string("lifetime", "$"), "lifetime")
        if (lifetime !in 1..1200) bad("lifetime", "projectile lifetime must be 1..1200 ticks")
        val collision = m["collision"]?.asMap("collision", "collision") ?: emptyMap()
        collision.only(setOf("entities", "blocks"), "collision")
        val entities = when (collision["entities"] ?: "enemies") {
            "enemies" -> ProjectileEntities.ENEMIES
            "allies" -> ProjectileEntities.ALLIES
            "any" -> ProjectileEntities.ANY
            else -> bad("collision.entities", "supported collision filters are enemies, allies, and any")
        }
        if ((collision["blocks"] ?: "solid") != "solid") bad("collision.blocks", "supported block collision is solid")
        val rawParameters = m["parameters"]?.asMap("parameters", "parameters") ?: emptyMap()
        if (rawParameters.size > 32) bad("parameters", "at most 32 projectile parameters are supported")
        val parameters = rawParameters.mapValues { (name, raw) ->
            val path = "parameters.$name"
            if (name.length > 64 || !Regex("[a-z0-9_]+").matches(name)) bad(path, "invalid parameter name")
            val parameter = raw.asMap(path, path)
            parameter.only(setOf("type", "min", "max", "default"), path)
            if (parameter.string("type", path) != "number") bad("$path.type", "supported projectile parameter type is number")
            val minimum = parameter.number("min", path)
            val maximum = parameter.number("max", path)
            if (minimum < -1_000_000.0 || maximum > 1_000_000.0 || minimum > maximum) bad(path, "invalid parameter bounds")
            val default = if ("default" in parameter) parameter.number("default", path).also {
                if (it !in minimum..maximum) bad("$path.default", "default is outside declared bounds")
            } else null
            NumberParameter(minimum, maximum, default)
        }
        val entityHit = nestedEffects(m, "entity_hit", "$", pack, 0, false)
        val blockHit = nestedEffects(m, "block_hit", "$", pack, 0, false)
        val expiry = nestedEffects(m, "expiry", "$", pack, 0, false)
        val pierce = m["pierce"]?.asMap("pierce", "pierce")?.let { policy ->
            policy.only(setOf("additional_entities"), "pierce")
            policy.integer("additional_entities", "pierce").also { if (it !in 1..16) bad("pierce.additional_entities", "must be 1..16") }
        } ?: 0
        val bounce = m["bounce"]?.asMap("bounce", "bounce")?.let { policy ->
            policy.only(setOf("blocks"), "bounce")
            policy.integer("blocks", "bounce").also { if (it !in 1..16) bad("bounce.blocks", "must be 1..16") }
        } ?: 0
        val repeat = m["repeat_hit"]?.asMap("repeat_hit", "repeat_hit")
        repeat?.only(setOf("max_per_entity", "interval"), "repeat_hit")
        val repeats = repeat?.integer("max_per_entity", "repeat_hit")?.also { if (it !in 2..8) bad("repeat_hit.max_per_entity", "must be 2..8") } ?: 1
        val repeatInterval = repeat?.let { ticks(it.string("interval", "repeat_hit"), "repeat_hit.interval").also { value ->
            if (value <= 0) bad("repeat_hit.interval", "repeat-hit interval must be positive")
        } } ?: 0
        if (repeat != null && pierce == 0) bad("repeat_hit", "repeat-hit policy needs at least one additional entity impact")
        val homing = m["homing"]?.asMap("homing", "homing")?.let { policy ->
            policy.only(setOf("turn_degrees_per_tick"), "homing")
            boundedNumber(policy, "turn_degrees_per_tick", "homing", 0.1, 180.0)
        }
        for ((name, body) in listOf("entity_hit" to entityHit, "block_hit" to blockHit, "expiry" to expiry)) {
            validateBindings(body, parameters.keys.map { "params.$it" }.toSet(), name)
            validateContexts(body, name == "entity_hit", true, name)
        }
        return ProjectileDef(id, speed, gravity, lifetime, entities, entityHit, blockHit, expiry, pierce, bounce, repeats, repeatInterval, homing, parameters)
    }

    private fun parseEmpowerment(m: Map<String, Any?>, id: String, pack: Pack): EmpowermentDef {
        m.only(setOf("kind", "id", "changes"), "$")
        val changes = m.listOrEmpty("changes", "$")
        if (changes.size !in 1..32) bad("changes", "empowerment needs 1..32 changes")
        val replacements = changes.mapIndexed { index, raw ->
            val field = "changes[$index]"
            val change = raw.asMap(field, field)
            change.only(setOf("type", "target", "replacement", "priority"), field)
            if (change.string("type", field) != "replace") bad("$field.type", "supported empowerment change is replace")
            val target = change["target"].asMap("$field.target", "$field.target")
            target.only(setOf("ability"), "$field.target")
            val grant = target.string("ability", "$field.target")
            if (!Regex("[a-z0-9_./-]{1,128}").matches(grant)) bad("$field.target.ability", "invalid grant name")
            val priority = (if ("priority" in change) change.integer("priority", field) else 0)
                .also { if (it !in -100..100) bad("$field.priority", "priority must be -100..100") }
            AbilityReplacement(grant, reference(change["replacement"], pack, "$field.replacement"), priority)
        }
        if (replacements.map { it.grant }.distinct().size != replacements.size) bad("changes", "duplicate empowerment replacement target")
        return EmpowermentDef(id, replacements)
    }

    private fun parseUnlockTree(m: Map<String, Any?>, id: String, pack: Pack): UnlockTreeDef {
        m.only(setOf("kind", "id", "track", "specializations", "nodes"), "$")
        val track = reference(m["track"], pack, "track")
        val specializations = m.listOrEmpty("specializations", "$").mapIndexed { index, value ->
            reference(value, pack, "specializations[$index]")
        }
        if (specializations.size > 128 || specializations.distinct().size != specializations.size)
            bad("specializations", "at most 128 distinct specializations are supported")
        val rawNodes = m["nodes"].asMap("nodes", "nodes")
        if (rawNodes.size !in 1..128) bad("nodes", "unlock tree needs 1..128 nodes")
        val nodes = rawNodes.map { (name, raw) ->
            val field = "nodes.$name"
            if (!Regex("[a-z0-9_./-]{1,64}").matches(name)) bad(field, "invalid node ID")
            val node = raw.asMap(field, field)
            node.only(setOf("selection", "requires", "prerequisites", "ranks", "choice_group", "cost", "grants"), field)
            val selection = when (node.string("selection", field)) {
                "automatic" -> UnlockSelection.AUTOMATIC
                "talent" -> UnlockSelection.TALENT
                else -> bad("$field.selection", "selection must be automatic or talent")
            }
            val requires = node["requires"]?.asMap("$field.requires", "$field.requires")
            requires?.only(setOf("type", "track", "level"), "$field.requires")
            val requiredLevel = requires?.let {
                if (it.string("type", "$field.requires") != "level_at_least") bad("$field.requires.type", "supported requirement is level_at_least")
                if (reference(it["track"], pack, "$field.requires.track") != track) bad("$field.requires.track", "requirement must use the tree track")
                it.integer("level", "$field.requires").also { value -> if (value !in 1..128) bad("$field.requires.level", "level must be 1..128") }
            } ?: 1
            val prerequisites = node.listOrEmpty("prerequisites", field).mapIndexed { index, value ->
                value.asString("$field.prerequisites[$index]").also {
                    if (!Regex("[a-z0-9_./-]{1,64}").matches(it)) bad("$field.prerequisites[$index]", "invalid prerequisite")
                }
            }
            if (prerequisites.size > 16 || prerequisites.distinct().size != prerequisites.size)
                bad("$field.prerequisites", "at most 16 distinct prerequisites")
            val ranks = if ("ranks" in node) node.integer("ranks", field) else 1
            if (ranks !in 1..16) bad("$field.ranks", "ranks must be 1..16")
            val choiceGroup = node["choice_group"]?.asString("$field.choice_group")?.also {
                if (!Regex("[a-z0-9_./-]{1,64}").matches(it)) bad("$field.choice_group", "invalid choice group")
            }
            val cost = node["cost"]?.asMap("$field.cost", "$field.cost")?.let { amount ->
                amount.only(setOf("budget", "amount"), "$field.cost")
                val budget = amount.string("budget", "$field.cost")
                if (!Regex("[a-z0-9_./-]{1,64}").matches(budget)) bad("$field.cost.budget", "invalid budget")
                PointCost(budget, amount.integer("amount", "$field.cost").also { if (it !in 1..1000) bad("$field.cost.amount", "cost must be 1..1000") })
            }
            if (selection == UnlockSelection.AUTOMATIC && (cost != null || choiceGroup != null))
                bad(field, "automatic nodes cannot spend points or join a choice group")
            val rawGrants = node.listOrEmpty("grants", field)
            if (rawGrants.size > 16) bad("$field.grants", "at most 16 grants per node")
            val empowerments = mutableListOf<String>()
            val abilities = mutableListOf<AbilityUnlock>()
            rawGrants.forEachIndexed { index, value ->
                val path = "$field.grants[$index]"
                val grant = value.asMap(path, path)
                when (grant.string("type", path)) {
                    "empowerment" -> {
                        grant.only(setOf("type", "ref"), path)
                        empowerments += reference(grant["ref"], pack, "$path.ref")
                    }
                    "ability" -> {
                        grant.only(setOf("type", "ref", "grant", "slot"), path)
                        val name = grant.string("grant", path)
                        if (name.length > 128 || !LOCAL_ID.matches(name)) bad("$path.grant", "invalid logical grant name")
                        val slot = grant["slot"]?.asString("$path.slot")
                        if (slot != null && (slot.length > 64 || !LOCAL_ID.matches(slot))) bad("$path.slot", "invalid slot name")
                        abilities += AbilityUnlock(name, reference(grant["ref"], pack, "$path.ref"), slot)
                    }
                    else -> bad("$path.type", "supported node grants are ability and empowerment")
                }
            }
            if (abilities.map { it.grant }.distinct().size != abilities.size) bad("$field.grants", "duplicate ability grant")
            name to UnlockNode(name, selection, requiredLevel, prerequisites.toSet(), ranks, choiceGroup, cost, empowerments, abilities)
        }.toMap()
        fun visit(name: String, visiting: MutableSet<String>, visited: MutableSet<String>) {
            if (name in visited) return
            if (!visiting.add(name)) bad("nodes.$name.prerequisites", "unlock prerequisite cycle")
            for (prerequisite in nodes.getValue(name).prerequisites) {
                if (prerequisite !in nodes) bad("nodes.$name.prerequisites", "unknown prerequisite $prerequisite")
                visit(prerequisite, visiting, visited)
            }
            visiting.remove(name)
            visited += name
        }
        val visited = mutableSetOf<String>()
        nodes.keys.forEach { visit(it, mutableSetOf(), visited) }
        return UnlockTreeDef(id, track, nodes, specializations.toSet())
    }

    private fun parseProgressionTrack(m: Map<String, Any?>, id: String, pack: Pack): ProgressionTrackDef {
        m.only(setOf("kind", "id", "scope", "classes", "levels", "cap", "earn"), "$")
        val scope = when (m.string("scope", "$")) {
            "player" -> ProgressScope.PLAYER
            "class" -> ProgressScope.CLASS
            else -> bad("scope", "progression scope must be player or class")
        }
        val classes = m.listOrEmpty("classes", "$").mapIndexed { index, value ->
            reference(value, pack, "classes[$index]")
        }
        if (classes.size > 128 || classes.distinct().size != classes.size) bad("classes", "at most 128 distinct classes are supported")
        if (scope == ProgressScope.PLAYER && classes.isNotEmpty()) bad("classes", "player-wide tracks cannot be class restricted")
        val rawLevels = m.listOrEmpty("levels", "$")
        if (rawLevels.size !in 1..128) bad("levels", "progression needs 1..128 levels")
        val awardIds = mutableSetOf<String>()
        var previousXp = -1L
        val levels = rawLevels.mapIndexed { index, raw ->
            val field = "levels[$index]"
            val level = raw.asMap(field, field)
            level.only(setOf("level", "xp", "awards"), field)
            if (level.integer("level", field) != index + 1) bad("$field.level", "levels must be contiguous from 1")
            val xp = progressionLong(level["xp"], "$field.xp")
            if ((index == 0 && xp != 0L) || xp <= previousXp) bad("$field.xp", "thresholds must start at zero and increase")
            previousXp = xp
            val rawAwards = level.listOrEmpty("awards", field)
            if (rawAwards.size > 16) bad("$field.awards", "at most 16 awards per level")
            val awards = rawAwards.mapIndexed { awardIndex, item ->
                val path = "$field.awards[$awardIndex]"
                val award = item.asMap(path, path)
                award.only(setOf("id", "type", "budget", "amount"), path)
                val awardId = award.string("id", path)
                if (!Regex("[a-z0-9_./-]{1,64}").matches(awardId) || !awardIds.add(awardId))
                    bad("$path.id", "award ID must be unique and stable")
                if (award.string("type", path) != "talent_points") bad("$path.type", "supported award type is talent_points")
                val budget = award.string("budget", path)
                if (!Regex("[a-z0-9_./-]{1,64}").matches(budget)) bad("$path.budget", "invalid budget ID")
                val amount = award.integer("amount", path)
                if (amount !in 1..1000) bad("$path.amount", "point award must be 1..1000")
                PointAward(awardId, budget, amount)
            }
            ProgressLevel(index + 1, xp, awards)
        }
        val cap = m["cap"]?.asMap("cap", "cap")
        cap?.only(setOf("level", "overflow"), "cap")
        val capLevel = cap?.let { (if ("level" in it) it.integer("level", "cap") else levels.size)
            .also { level -> if (level !in 1..levels.size) bad("cap.level", "cap level is outside the track") } }
        val bankOverflow = when (cap?.get("overflow") ?: "stop") {
            "stop" -> false
            "bank" -> true
            else -> bad("cap.overflow", "overflow must be stop or bank")
        }
        val earn = m["earn"]?.asMap("earn", "earn") ?: emptyMap()
        if (earn.size > 64) bad("earn", "at most 64 earning rules are supported")
        val rules = earn.map { (name, raw) ->
            if (!Regex("[a-z0-9_./-]{1,64}").matches(name)) bad("earn.$name", "invalid earning rule ID")
            val field = "earn.$name"
            val rule = raw.asMap(field, field)
            rule.only(setOf("event", "phase", "when", "amount", "xp", "recipients", "distribution"), field)
            val event = when (rule.string("event", field)) {
                "entity_death" -> ProgressEvent.ENTITY_DEATH
                "vanilla_xp" -> ProgressEvent.VANILLA_XP
                else -> bad("$field.event", "unsupported progression event")
            }
            if (rule["phase"] != null && rule.string("phase", field) != "after") bad("$field.phase", "progression awards require the after phase")
            if (event == ProgressEvent.VANILLA_XP && "when" in rule)
                bad("$field.when", "vanilla XP has no credited kill source")
            rule["when"]?.asMap("$field.when", "$field.when")?.let { condition ->
                condition.only(setOf("type"), "$field.when")
                if (condition.string("type", "$field.when") != "credited_to_owner")
                    bad("$field.when.type", "supported earning condition is credited_to_owner")
            }
            if ("xp" in rule && "amount" in rule) bad(field, "use amount or xp, not both")
            val xp = progressionLong(rule["amount"] ?: rule["xp"] ?: 1, "$field.amount")
            if (xp !in 1..1_000_000L) bad("$field.amount", "earning amount must be 1..1000000")
            val recipients = rule["recipients"]?.asMap("$field.recipients", "$field.recipients")
            recipients?.only(setOf("type", "range"), "$field.recipients")
            val recipientType = when (recipients?.string("type", "$field.recipients") ?: "actor") {
                "actor" -> ProgressRecipients.ACTOR
                "contributors" -> ProgressRecipients.CONTRIBUTORS
                "nearby_allies" -> ProgressRecipients.NEARBY_ALLIES
                else -> bad("$field.recipients.type", "supported recipients are actor, contributors, and nearby_allies")
            }
            val range = if (recipientType == ProgressRecipients.NEARBY_ALLIES) {
                boundedNumber(recipients!!, "range", "$field.recipients", 0.1, 64.0)
            } else {
                if (recipients?.containsKey("range") == true) bad("$field.recipients.range", "only nearby_allies recipients use range")
                0.0
            }
            val distribution = when (rule["distribution"] ?: "each") {
                "each" -> ProgressDistribution.EACH
                "split" -> ProgressDistribution.SPLIT
                else -> bad("$field.distribution", "distribution must be each or split")
            }
            if (recipientType == ProgressRecipients.CONTRIBUTORS && "when" in rule)
                bad("$field.when", "contributor rewards do not use a single credited-killer condition")
            if (event == ProgressEvent.VANILLA_XP &&
                (recipientType != ProgressRecipients.ACTOR || distribution != ProgressDistribution.EACH))
                bad(field, "vanilla XP must award only its recipient")
            EarningRule(name, event, xp, recipientType, range, distribution)
        }
        return ProgressionTrackDef(id, scope, levels, capLevel, bankOverflow, rules, classes.toSet())
    }

    private fun progressionLong(raw: Any?, field: String): Long {
        val source = raw?.toString() ?: bad(field, "missing nonnegative integer")
        if (!Regex("[0-9]+").matches(source)) bad(field, "expected a nonnegative integer")
        return source.toLongOrNull()?.takeIf { it <= 1_000_000_000_000L }
            ?: bad(field, "progression value exceeds 1000000000000")
    }

    private fun parseStatus(m: Map<String, Any?>, id: String, pack: Pack): StatusDef {
        m.only(setOf("kind", "id", "state", "duration", "reapply", "stacks", "periodic", "applied", "refreshed", "stacks_changed", "expired", "broken", "break_on_damage", "modifiers", "tags", "restrictions", "control_categories", "immunities"), "$")
        val duration = ticks(m.string("duration", "$"), "duration")
        if (duration <= 0) bad("duration", "status lifetime must be positive")
        if (m["reapply"] != null && m.string("reapply", "$") != "refresh") bad("reapply", "supported reapplication policy is refresh")
        val stacks = m["stacks"]?.asMap("stacks", "stacks")?.let {
            it.only(setOf("max", "duration"), "stacks")
            val maximum = it.integer("max", "stacks")
            if (maximum !in 1..64) bad("stacks.max", "stack cap must be 1..64")
            val mode = when (it["duration"] ?: "shared") {
                "shared" -> StackDuration.SHARED
                "per_stack" -> StackDuration.PER_STACK
                else -> bad("stacks.duration", "supported stack duration modes are shared and per_stack")
            }
            StatusStacks(maximum, mode)
        }
        var interval = 0
        val periodic = m["periodic"]?.asMap("periodic", "periodic")?.let {
            it.only(setOf("every", "effects"), "periodic")
            interval = ticks(it.string("every", "periodic"), "periodic.every")
            if (interval <= 0) bad("periodic.every", "periodic interval must be positive")
            nestedEffects(it, "effects", "periodic", pack, 0)
        }.orEmpty()
        val callbacks = listOf("applied", "refreshed", "stacks_changed", "expired", "broken").associateWith { nestedEffects(m, it, "$", pack, 0, false) }
        for ((field, body) in callbacks + ("periodic.effects" to periodic)) {
            validateBindings(body, setOf("status.stacks"), field)
            validateContexts(body, true, false, field)
        }
        val modifiers = m.listOrEmpty("modifiers", "$")
        if (modifiers.size > 8) bad("modifiers", "at most 8 modifiers are supported")
        var speed: SpeedBonus? = null
        var reflect: ReflectPolicy? = null
        val replacements = mutableListOf<AbilityReplacement>()
        for ((index, raw) in modifiers.withIndex()) {
            val field = "modifiers[$index]"
            val modifier = raw.asMap(field, field)
            when (modifier.string("type", field)) {
                "attribute" -> {
                    if (speed != null) bad(field, "one movement speed modifier is supported")
                    modifier.only(setOf("type", "attribute", "amount", "stacking", "cap"), field)
                    if (modifier.string("attribute", field) != "minecraft:movement_speed") bad(field, "supported attribute is minecraft:movement_speed")
                    val amount = boundedNumber(modifier, "amount", field, 0.0, 1.0)
                    val combination = when (modifier["stacking"] ?: "strongest") {
                        "strongest" -> BonusCombination.STRONGEST
                        "capped_add" -> BonusCombination.CAPPED_ADD
                        else -> bad("$field.stacking", "supported policies are strongest and capped_add")
                    }
                    val cap = if (combination == BonusCombination.CAPPED_ADD) boundedNumber(modifier, "cap", field, 0.0, 1.0) else null
                    if (combination == BonusCombination.STRONGEST && "cap" in modifier) bad("$field.cap", "a cap requires capped_add")
                    if (cap != null && amount > cap) bad("$field.amount", "bonus exceeds its addition cap")
                    speed = SpeedBonus(amount, combination, cap)
                }
                "replace" -> {
                    modifier.only(setOf("type", "target", "replacement", "priority"), field)
                    val target = modifier["target"].asMap("$field.target", "$field.target")
                    target.only(setOf("ability"), "$field.target")
                    val grant = target.string("ability", "$field.target")
                    if (!Regex("[a-z0-9_./-]{1,128}").matches(grant)) bad("$field.target.ability", "invalid grant name")
                    if (replacements.any { it.grant == grant }) bad("$field.target.ability", "duplicate replacement target")
                    val replacement = reference(modifier["replacement"], pack, "$field.replacement")
                    val priority = (if ("priority" in modifier) modifier.integer("priority", field) else 0)
                        .also { if (it !in -100..100) bad("$field.priority", "priority must be -100..100") }
                    replacements += AbilityReplacement(grant, replacement, priority)
                }
                "reflect" -> {
                    if (reflect != null) bad(field, "one reflection policy is supported")
                    modifier.only(setOf("type", "fraction", "cap", "damage_type"), field)
                    val fraction = boundedNumber(modifier, "fraction", field, 0.0, 1.0)
                    if (fraction <= 0.0) bad("$field.fraction", "reflection fraction must be positive")
                    val cap = boundedNumber(modifier, "cap", field, 0.01, 128.0)
                    val damageType = modifier.string("damage_type", field)
                    if (damageType.length > 128 || !GLOBAL_ID.matches(damageType))
                        bad("$field.damage_type", "invalid native damage type")
                    reflect = ReflectPolicy(fraction, cap, damageType)
                }
                else -> bad("$field.type", "unsupported modifier type")
            }
        }
        val restrictions = m.listOrEmpty("restrictions", "$")
        if (restrictions.size > 4) bad("restrictions", "at most four distinct restrictions are supported")
        val actions = restrictions.mapIndexed { index, value ->
            when (value.asString("restrictions[$index]")) {
                "activate" -> ActionRestriction.ACTIVATE
                "move" -> ActionRestriction.MOVE
                "jump" -> ActionRestriction.JUMP
                "attack" -> ActionRestriction.ATTACK
                else -> bad("restrictions[$index]", "supported restrictions are activate, move, jump, attack")
            }
        }.toSet()
        if (actions.size != restrictions.size) bad("restrictions", "duplicate restriction")
        val controlCategories = statusLabels(m, "control_categories", pack, "$")
        val immunities = statusLabels(m, "immunities", pack, "$")
        if (controlCategories.any { it in immunities }) bad("immunities", "a status cannot be immune to its own control category")
        val breakOnHealthLoss = m["break_on_damage"]?.asMap("break_on_damage", "break_on_damage")?.let { rule ->
            rule.only(setOf("minimum_health_loss"), "break_on_damage")
            boundedNumber(rule, "minimum_health_loss", "break_on_damage", 0.0, 1_000_000.0, 0.0)
        }
        if (breakOnHealthLoss == null && callbacks.getValue("broken").isNotEmpty()) bad("broken", "broken callback requires break_on_damage")
        return StatusDef(id, duration, stacks, interval, periodic, callbacks.getValue("applied"), callbacks.getValue("refreshed"), callbacks.getValue("stacks_changed"), callbacks.getValue("expired"), speed, statusTags(m, pack, "$"), actions, controlCategories, immunities, breakOnHealthLoss, callbacks.getValue("broken"),
            m["state"]?.let { reference(it, pack, "state") }, replacements, reflect)
    }

    private fun validateContexts(effects: List<Effect>, entity: Boolean, position: Boolean, field: String) {
        for ((index, effect) in effects.withIndex()) {
            val mechanic = catalog.mechanic(effect)
            val location = "$field[$index]"
            if (mechanic.needsEntityTarget(effect) && !entity) bad(location, "entity target is not available in this context")
            if (mechanic.needsTarget(effect) && !entity && !position) bad(location, "target is not available in this context")
            if (mechanic.needsGround(effect) && !position) bad(location, "ground position is not available in this context")
            if (effect is Effect.WaitFor) {
                validateContexts(effect.matched, entity || effect.event == ProjectileEvent.ENTITY_HIT, true, "$location.matched")
                validateContexts(effect.timedOut, entity, position, "$location.timed_out")
                continue
            }
            for (body in mechanic.nested(effect)) validateContexts(body, entity || mechanic.providesTarget, position, "$location.effects")
        }
    }

    private fun validateBindings(effects: List<Effect>, incoming: Set<String>, field: String): Set<String> {
        val available = incoming.toMutableSet()
        for ((index, effect) in effects.withIndex()) {
            val location = "$field[$index]"
            val mechanic = catalog.mechanic(effect)
            if (effect !is Effect.Parallel) for ((input, numeric) in mechanic.inputs(effect)) checkNumeric(numeric, available, "$location.$input")
            if (effect is Effect.WaitFor && (!Regex("result\\.[a-z0-9_]+\\.handle").matches(effect.handle) || effect.handle !in available))
                bad("$location.handle", "wait_for requires an available projectile handle result")
            when (effect) {
                is Effect.Branch -> {
                    val whenTrue = validateBindings(effect.onTrue, available, "$location.then")
                    val whenFalse = validateBindings(effect.onFalse, available, "$location.else")
                    available += whenTrue.intersect(whenFalse)
                }
                is Effect.Choose -> {
                    val common = effect.options.mapIndexed { option, branch -> validateBindings(branch.effects, available, "$location.options[$option].effects") }
                        .reduce(Set<String>::intersect)
                    available += common
                }
                is Effect.Sequence -> available += validateBindings(effect.effects, available, "$location.effects")
                is Effect.Parallel -> {
                    for ((branchIndex, branch) in effect.branches.withIndex()) {
                        val branchAvailable = validateBindings(branch.effects, available, "$location.branches[$branchIndex].effects")
                        for (leaf in branch.successWhen?.leaves().orEmpty()) when (leaf) {
                            is Condition.Compare -> { checkNumeric(leaf.left, branchAvailable, "$location.branches[$branchIndex].success_when.left");
                                checkNumeric(leaf.right, branchAvailable, "$location.branches[$branchIndex].success_when.right") }
                            is Condition.ResourceAtLeast -> checkNumeric(leaf.amount, branchAvailable, "$location.branches[$branchIndex].success_when.amount")
                            else -> Unit
                        }
                    }
                    validateBindings(effect.then, available + mechanic.localBindings, "$location.then")
                    validateBindings(effect.failed, available + mechanic.localBindings, "$location.failed")
                }
                else -> for (body in mechanic.nested(effect)) validateBindings(body, available + mechanic.localBindings, "$location.effects")
            }
            effect.resultName?.let { name ->
                if (available.any { it.startsWith("result.$name.") }) bad("$location.as", "duplicate result name")
                available += mechanic.resultFields.map { "result.$name.$it" }
            }
        }
        return available
    }

    private fun checkNumeric(numeric: Numeric, available: Set<String>, field: String, allowState: Boolean = true) {
        if (numeric !is Numeric.Expression) return
        for (read in Expression.variables(numeric.source)) {
            if (read.endsWith(".handle")) bad(field, "projectile handles are not numeric values")
            if (read !in available && (!allowState || !read.startsWith("state."))) bad(field, "$read is not available here")
        }
    }

    private fun parseEffect(m: Map<String, Any?>, field: String, pack: Pack, depth: Int): Effect {
        if (depth > 8) bad(field, "effect nesting exceeds 8 levels")
        val type = m.string("type", field)
        val mechanic = catalog.effects[if (':' in type) type else "archetype:$type"] ?: bad("$field.type", "unsupported effect type $type")
        // ASVS 2.2.1-2.2.3: registration metadata defines the accepted fields before typed decoding.
        m.only(mechanic.fields.keys + setOf("type", "id") + if (mechanic.resultFields.isNotEmpty()) setOf("as") else emptySet(), field)
        for (required in mechanic.required) if (required !in m) bad("$field.$required", "required field is missing")
        val name = m["as"]?.asString("$field.as")
        val identity = m["id"]?.asString("$field.id") ?: field
        if ("id" in m && (identity.length > 128 || !LOCAL_ID.matches(identity))) bad("$field.id", "invalid effect identity")
        if (name != null && !Regex("[a-z0-9_]{1,64}").matches(name)) bad("$field.as", "result name must use lowercase letters, digits or underscores")
        return try { mechanic.decode(object : EffectReader {
            override val resultName = name
            override val identity = identity
            override fun has(key: String) = key in m
            override fun text(key: String) = m.string(key, field)
            override fun localId(key: String): String {
                val value = text(key)
                if (value.length > 64 || !LOCAL_ID.matches(value)) bad("$field.$key", "expected a local name of at most 64 characters")
                return value
            }
            override fun grant(key: String): String {
                val value = text(key)
                if (value.length > 128 || !LOCAL_ID.matches(value)) bad("$field.$key", "expected a local grant name of at most 128 characters")
                return value
            }
            override fun option(key: String, choices: Set<String>, default: String): String {
                val value = if (key in m) text(key) else default
                if (value !in choices) bad("$field.$key", "supported values are ${choices.joinToString()}")
                return value
            }
            override fun integer(key: String, min: Int, max: Int, default: Int?): Int {
                val value = if (key !in m && default != null) default else m.integer(key, field)
                if (value !in min..max) bad("$field.$key", "must be $min..$max")
                return value
            }
            override fun number(key: String, min: Double, max: Double, default: Double?) = boundedNumber(m, key, field, min, max, default)
            override fun boolean(key: String, default: Boolean) = booleanValue(m, key, field, default)
            override fun numeric(key: String) = this@ManifestCompiler.numeric(m[key], "$field.$key")
            override fun stateInput(key: String): StateInput = when (val value = m[key]) {
                is Boolean -> StateInput.Flag(value)
                is String -> StateInput.Mode(value.also { if (it.length > 64 || !LOCAL_ID.matches(it)) bad("$field.$key", "invalid state mode") })
                else -> StateInput.Number(this@ManifestCompiler.numeric(value, "$field.$key"))
            }
            override fun duration(key: String, positive: Boolean, default: Int?): Int {
                val value = if (key !in m && default != null) default else ticks(text(key), "$field.$key")
                if (positive && value <= 0) bad("$field.$key", "interval must be positive")
                return value
            }
            override fun reference(key: String): String {
                return reference(m[key], pack, "$field.$key")
            }
            override fun referenceWith(key: String): Pair<String, Map<String, Numeric>> {
                val path = "$field.$key"
                val value = m[key]
                if (value !is Map<*, *>) return reference(value, pack, path) to emptyMap()
                val call = value.asMap(path, path)
                call.only(setOf("ref", "with"), path)
                val id = reference(mapOf("ref" to call["ref"]), pack, path)
                val supplied = call["with"]?.asMap("$path.with", "$path.with") ?: emptyMap()
                if (supplied.size > 32) bad("$path.with", "at most 32 arguments are supported")
                val arguments = supplied.mapValues { (name, raw) ->
                    if (name.length > 64 || !Regex("[a-z0-9_]+").matches(name)) bad("$path.with.$name", "invalid parameter name")
                    numeric(raw, "$path.with.$name")
                }
                return id to arguments
            }
            override fun target(key: String) = parseTarget(text(key), "$field.$key")
            override fun spatialTarget(key: String, default: SpatialTarget) = if (key !in m) default else parseSpatial(text(key), "$field.$key")
            override fun effects(key: String, required: Boolean) = nestedEffects(m, key, field, pack, depth, required)
            override fun choices(key: String): List<WeightedBranch> {
                val values = m.listOrEmpty(key, field)
                if (values.size !in 1..16) bad("$field.$key", "choice list must contain 1..16 branches")
                val branches = values.mapIndexed { index, value ->
                    val path = "$field.$key[$index]"
                    val option = value.asMap(path, path)
                    option.only(setOf("weight", "effects"), path)
                    val weight = option.integer("weight", path)
                    if (weight !in 1..1000) bad("$path.weight", "weight must be 1..1000")
                    WeightedBranch(weight, nestedEffects(option, "effects", path, pack, depth))
                }
                if (branches.sumOf(WeightedBranch::weight) > 10_000) bad("$field.$key", "total choice weight exceeds 10000")
                return branches
            }
            override fun parallelBranches(key: String): List<ParallelBranch> {
                val values = m.listOrEmpty(key, field)
                if (values.size !in 2..8) bad("$field.$key", "parallel needs 2..8 branches")
                return values.mapIndexed { index, raw ->
                    val path = "$field.$key[$index]"
                    val branch = raw.asMap(path, path)
                    branch.only(setOf("after", "effects", "success_when"), path)
                    val after = branch["after"]?.asString("$path.after")?.let { ticks(it, "$path.after") } ?: 0
                    ParallelBranch(after, nestedEffects(branch, "effects", path, pack, depth),
                        branch["success_when"]?.let { parseCondition(it.asMap("$path.success_when", "$path.success_when"), "$path.success_when", pack) })
                }
            }
            override fun condition(key: String) = parseCondition(m[key].asMap("$field.$key", "$field.$key"), "$field.$key", pack)
            override fun statusFilter() = parseStatusFilter(m, pack, field)
            override fun selector(key: String, shape: Shape?) = parseSelector(m[key].asMap("$field.$key", "$field.$key"), "$field.$key", shape)
            override fun anchor(key: String): Anchor {
                val anchor = m[key].asMap("$field.$key", "$field.$key")
                anchor.only(setOf("position", "attached"), "$field.$key")
                if (("position" in anchor) == ("attached" in anchor)) bad("$field.$key", "anchor needs exactly one of position or attached")
                return if ("position" in anchor) Anchor.Fixed(parseSpatial(anchor.string("position", "$field.$key"), "$field.$key.position"))
                else Anchor.Attached(parseTarget(anchor.string("attached", "$field.$key"), "$field.$key.attached"))
            }
            override fun terrainRegion(key: String): TerrainRegion = parseTerrainRegion(m[key], "$field.$key")
            override fun terrainFilter(key: String): TerrainFilter = parseTerrainFilter(m[key], "$field.$key")
        }) } catch (failure: IllegalArgumentException) {
            bad(field, failure.message ?: "invalid effect")
        }
    }

    private fun parseTerrainRegion(value: Any?, field: String): TerrainRegion {
        val m = value.asMap(field, field)
        return when (m.string("type", field)) {
            "point" -> { m.only(setOf("type"), field); TerrainRegion.Point }
            "line" -> {
                m.only(setOf("type", "to"), field)
                val vector = terrainInts(m["to"], "$field.to", 3, -16..16)
                if (vector.all { it == 0 }) bad("$field.to", "line endpoint must differ from its origin")
                TerrainRegion.Line(vector[0], vector[1], vector[2])
            }
            "box" -> {
                m.only(setOf("type", "size"), field)
                val size = terrainInts(m["size"], "$field.size", 3, 1..16)
                if (size.fold(1L) { total, dimension -> total * dimension } > 256L)
                    bad("$field.size", "box may edit at most 256 cells")
                TerrainRegion.Box(size[0], size[1], size[2])
            }
            "sphere" -> {
                m.only(setOf("type", "radius"), field)
                val radius = m.integer("radius", field)
                if (radius !in 1..3) bad("$field.radius", "sphere radius must be 1..3")
                TerrainRegion.Sphere(radius)
            }
            else -> bad("$field.type", "supported terrain regions are point, line, box, and sphere")
        }
    }

    private fun terrainInts(value: Any?, field: String, count: Int, range: IntRange): List<Int> {
        val list = value as? List<*> ?: bad(field, "expected a coordinate list")
        if (list.size != count || list.any { it !is Int && it !is Long } ||
            list.any { (it as Number).toLong() !in range.first.toLong()..range.last.toLong() })
            bad(field, "expected $count integers in ${range.first}..${range.last}")
        return list.map { (it as Number).toInt() }
    }

    private fun parseTerrainFilter(value: Any?, field: String): TerrainFilter {
        val m = value.asMap(field, field)
        m.only(setOf("blocks", "tags"), field)
        fun ids(key: String): Set<String> {
            val values = m.listOrEmpty(key, field)
            if (values.size > 32) bad("$field.$key", "at most 32 filters are supported")
            return values.mapIndexed { index, raw ->
                raw.asString("$field.$key.$index").also {
                    if (it.length > 128 || !GLOBAL_ID.matches(it)) bad("$field.$key.$index", "expected a namespaced ID")
                }
            }.toSet()
        }
        val filter = TerrainFilter(ids("blocks"), ids("tags"))
        if (!filter.defined) bad(field, "filter needs at least one block or tag")
        return filter
    }

    private fun parseTarget(text: String, field: String): EffectTarget = when (text) {
        "actor" -> EffectTarget.ACTOR
        "target", "event.target" -> EffectTarget.TARGET
        else -> bad(field, "supported entity targets are actor and target")
    }

    private fun reference(value: Any?, pack: Pack, field: String): String {
        val text = if (value is Map<*, *>) {
            val mapping = value.asMap(field, field)
            mapping.only(setOf("ref"), field)
            mapping.string("ref", field)
        } else value.asString(field)
        return qualify(text, pack.id, pack, field)
    }

    private fun parseSpatial(text: String, field: String): SpatialTarget = when (text) {
        "actor" -> SpatialTarget.ACTOR
        "target" -> SpatialTarget.TARGET
        "ground", "event.position" -> SpatialTarget.GROUND
        else -> bad(field, "supported positions are actor, target and ground")
    }

    private fun boundedNumber(m: Map<String, Any?>, key: String, field: String, min: Double, max: Double, default: Double? = null): Double {
        val value = if (key !in m && default != null) default else m.number(key, field)
        if (value !in min..max) bad("$field.$key", "must be between $min and $max")
        return value
    }

    private fun booleanValue(m: Map<String, Any?>, key: String, field: String, default: Boolean): Boolean {
        if (key !in m) return default
        return m[key] as? Boolean ?: bad("$field.$key", "expected a Boolean")
    }

    private fun parseShape(m: Map<String, Any?>, field: String): Shape {
        fun size(key: String, default: Double? = null) = boundedNumber(m, key, field, 0.01, 32.0, default)
        val shape = when (m.string("type", field)) {
            "point" -> { m.only(setOf("type", "radius"), field); Shape.Point(size("radius", 0.25)) }
            "sphere" -> { m.only(setOf("type", "radius"), field); Shape.Sphere(size("radius")) }
            "cylinder" -> { m.only(setOf("type", "radius", "height"), field); Shape.Cylinder(size("radius"), size("height")) }
            "ring" -> {
                m.only(setOf("type", "inner_radius", "outer_radius", "height"), field)
                val inner = boundedNumber(m, "inner_radius", field, 0.0, 32.0)
                val outer = size("outer_radius")
                if (inner >= outer) bad("$field.inner_radius", "inner radius must be smaller than outer radius")
                Shape.Ring(inner, outer, size("height"))
            }
            "box" -> { m.only(setOf("type", "width", "height", "depth"), field); Shape.Box(size("width"), size("height"), size("depth")) }
            "segment", "beam" -> { m.only(setOf("type", "length", "radius"), field); Shape.Segment(size("length"), size("radius")) }
            "cone" -> { m.only(setOf("type", "range", "angle"), field); Shape.Cone(size("range"), boundedNumber(m, "angle", field, 0.01, 180.0)) }
            else -> bad("$field.type", "unsupported shape")
        }
        if (shape.bound > 32.0) bad(field, "shape must fit within a 32-block enclosing radius")
        return shape
    }

    private fun parseSelector(m: Map<String, Any?>, field: String, suppliedShape: Shape? = null): Selector {
        m.only(setOf("type", "limit", "filters", "include_actor", "order", "line_of_sight") + if (suppliedShape == null) setOf("shape") else emptySet(), field)
        if (m.string("type", field) != "living_entities") bad("$field.type", "supported selector type is living_entities")
        val shape = suppliedShape ?: parseShape(m["shape"].asMap("$field.shape", "$field.shape"), "$field.shape")
        val limit = if ("limit" in m) m.integer("limit", field) else 16
        if (limit !in 1..64) bad("$field.limit", "limit must be 1..64")
        var relation = Relation.ANY
        var minimumHealth = 0.0
        var maximumHealth = 1.0
        var maximumFacingAngle: Double? = null
        var entityType: String? = null
        val seen = mutableSetOf<String>()
        val filters = m.listOrEmpty("filters", field)
        if (filters.size > 8) bad("$field.filters", "at most 8 filters are supported")
        for ((index, value) in filters.withIndex()) {
            val path = "$field.filters[$index]"
            val filter = value.asMap(path, path)
            val type = filter.string("type", path)
            if (!seen.add(type)) bad("$path.type", "duplicate filter type")
            when (type) {
                "relation" -> {
                    filter.only(setOf("type", "is"), path)
                    relation = when (filter.string("is", path)) {
                        "any" -> Relation.ANY
                        "ally" -> Relation.ALLY
                        "enemy" -> Relation.ENEMY
                        "self" -> Relation.SELF
                        else -> bad("$path.is", "unsupported relationship")
                    }
                }
                "health_fraction" -> {
                    filter.only(setOf("type", "min", "max"), path)
                    minimumHealth = boundedNumber(filter, "min", path, 0.0, 1.0, 0.0)
                    maximumHealth = boundedNumber(filter, "max", path, 0.0, 1.0, 1.0)
                    if (minimumHealth > maximumHealth) bad(path, "health minimum exceeds maximum")
                }
                "facing_origin" -> {
                    filter.only(setOf("type", "max_degrees"), path)
                    maximumFacingAngle = boundedNumber(filter, "max_degrees", path, 0.0, 180.0, 45.0)
                }
                "entity_type" -> {
                    filter.only(setOf("type", "id"), path)
                    entityType = filter.string("id", path).also {
                        if (it.length > 128 || !GLOBAL_ID.matches(it)) bad("$path.id", "invalid entity type ID")
                    }
                }
                else -> bad("$path.type", "unsupported selector filter")
            }
        }
        val order = when (m["order"] ?: "nearest") {
            "nearest" -> TargetOrder.NEAREST
            "farthest" -> TargetOrder.FARTHEST
            "lowest_health" -> TargetOrder.LOWEST_HEALTH
            "highest_health" -> TargetOrder.HIGHEST_HEALTH
            "random" -> TargetOrder.RANDOM
            else -> bad("$field.order", "unsupported target order")
        }
        return Selector(shape, limit, relation, booleanValue(m, "line_of_sight", field, true), booleanValue(m, "include_actor", field, false), order, minimumHealth, maximumHealth, maximumFacingAngle, entityType)
    }

    private fun nestedEffects(
        m: Map<String, Any?>, key: String, field: String, pack: Pack, depth: Int, required: Boolean = true,
    ): List<Effect> {
        val effects = m.listOrEmpty(key, field)
        if (required && effects.isEmpty()) bad("$field.$key", "effect list must not be empty")
        if (effects.size > 64) bad("$field.$key", "effect list exceeds 64 steps")
        return effects.mapIndexed { index, value ->
            val childField = "$field.$key[$index]"
            parseEffect(value.asMap(childField, childField), childField, pack, depth + 1)
        }
    }

    private fun parseCondition(m: Map<String, Any?>, field: String, pack: Pack, depth: Int = 0, nodes: IntArray = intArrayOf(0)): Condition {
        // ASVS 2.2.1, 2.3.2: finite Boolean trees cannot multiply evaluation work without bound.
        if (depth > 8 || ++nodes[0] > 128) bad(field, "condition tree exceeds its depth or node limit")
        fun children(key: String): List<Condition> {
            m.only(setOf(key), field)
            val values = m.listOrEmpty(key, field)
            if (values.size !in 1..16) bad("$field.$key", "condition list must contain 1..16 entries")
            return values.mapIndexed { index, value ->
                val path = "$field.$key[$index]"
                parseCondition(value.asMap(path, path), path, pack, depth + 1, nodes)
            }
        }
        return when {
            "all" in m -> Condition.All(children("all"))
            "any" in m -> Condition.Any(children("any"))
            "not" in m -> {
                m.only(setOf("not"), field)
                Condition.Not(parseCondition(m["not"].asMap("$field.not", "$field.not"), "$field.not", pack, depth + 1, nodes))
            }
            else -> when (m.string("type", field)) {
                "has_status" -> {
                    m.only(setOf("type", "target", "status", "tags", "source"), field)
                    Condition.HasStatus(parseTarget(m.string("target", field), "$field.target"), parseStatusFilter(m, pack, field))
                }
                "resource_at_least" -> {
                    m.only(setOf("type", "resource", "amount"), field)
                    Condition.ResourceAtLeast(qualify(m.string("resource", field), pack.id, pack, "$field.resource"), numeric(m["amount"], "$field.amount"))
                }
                "state_is" -> {
                    m.only(setOf("type", "state", "field", "value"), field)
                    val state = qualify(m.string("state", field), pack.id, pack, "$field.state")
                    val name = m.string("field", field)
                    if (name.length > 64 || !LOCAL_ID.matches(name)) bad("$field.field", "invalid state field")
                    val value = when (val raw = m["value"]) {
                        is Boolean -> StateValue.Flag(raw)
                        is Number -> StateValue.Number(raw.toDouble().also { if (!it.isFinite()) bad("$field.value", "expected a finite number") })
                        is String -> StateValue.Mode(raw)
                        else -> bad("$field.value", "expected a Boolean, number, or mode")
                    }
                    Condition.StateIs(state, name, value)
                }
                "compare" -> {
                    m.only(setOf("type", "left", "op", "right"), field)
                    val operator = m.string("op", field)
                    if (operator !in setOf("lt", "lte", "eq", "gte", "gt")) bad("$field.op", "unsupported comparison")
                    Condition.Compare(numeric(m["left"], "$field.left"), operator, numeric(m["right"], "$field.right"))
                }
                "chance" -> {
                    m.only(setOf("type", "probability"), field)
                    Condition.Chance(boundedNumber(m, "probability", field, 0.0, 1.0))
                }
                else -> bad("$field.type", "unsupported condition type")
            }
        }
    }

    private fun statusLabels(m: Map<String, Any?>, name: String, pack: Pack, field: String): Set<String> {
        if (name !in m) return emptySet()
        val location = if (field == "$") name else "$field.$name"
        val raw = m.listOrEmpty(name, field)
        // ASVS 2.2.1, 2.2.3: bounded labels share namespace validation and reject normalized duplicates.
        if (raw.size !in 1..16) bad(location, "$name must contain 1..16 distinct namespaced or local labels")
        val tags = raw.mapIndexed { index, value -> qualify(value.asString("$location[$index]"), pack.id, pack, "$location[$index]") }
        if (tags.distinct().size != tags.size) bad(location, "duplicate $name label")
        return tags.toSet()
    }

    private fun statusTags(m: Map<String, Any?>, pack: Pack, field: String) = statusLabels(m, "tags", pack, field)

    private fun parseStatusFilter(m: Map<String, Any?>, pack: Pack, field: String): StatusFilter {
        val source = when (if ("source" in m) m["source"] else "any") {
            "any" -> StatusSource.ANY
            "actor" -> StatusSource.ACTOR
            "grant" -> StatusSource.GRANT
            else -> bad("$field.source", "supported status sources are any, actor and grant")
        }
        return StatusFilter(if ("status" in m) reference(m["status"], pack, "$field.status") else null, statusTags(m, pack, field), source)
    }

    private fun parseClass(m: Map<String, Any?>, id: String, pack: Pack, abilities: Map<String, AbilityDef>): ClassDef {
        m.only(setOf("kind", "id", "name", "abilities"), "$")
        val grants = linkedMapOf<String, Grant>()
        val raw = m["abilities"].asMap("abilities", "abilities")
        if (raw.size > 128) bad("abilities", "at most 128 grants are supported per class")
        for ((name, value) in raw) {
            if (name.length > 128 || !LOCAL_ID.matches(name)) bad("abilities.$name", "invalid grant name")
            val field = "abilities.$name"
            val grant = value.asMap(field, field)
            grant.only(setOf("ref", "definition", "slot"), field)
            val hasRef = "ref" in grant
            val hasDefinition = "definition" in grant
            if (hasRef == hasDefinition) bad(field, "grant needs exactly one of ref or definition")
            val ability = if (hasRef) {
                val ref = qualify(grant.string("ref", field), pack.id, pack, "$field.ref")
                abilities[ref] ?: bad("$field.ref", "unknown ability $ref")
            } else {
                parseAbility(grant["definition"].asMap("$field.definition", "$field.definition"), "$id/$name", false, pack)
            }
            val slot = grant["slot"]?.asString("$field.slot")
            if (slot != null && (slot.length > 64 || !LOCAL_ID.matches(slot))) bad("$field.slot", "invalid slot name")
            grants[name] = Grant(name, slot, ability)
        }
        return ClassDef(id, m.string("name", "$"), grants)
    }

    private fun qualify(value: String, owner: String, pack: Pack, field: String): String {
        val id = if (':' in value) value else "$owner:$value"
        if (id.length > 128 || !GLOBAL_ID.matches(id)) bad(field, "invalid namespaced ID")
        if (id.substringBefore(':') !in pack.dependencies + pack.id) bad(field, "cross-pack reference needs a declared dependency")
        return id
    }

    private fun numeric(value: Any?, field: String): Numeric = when (value) {
        is Number -> Numeric.Constant(value.toDouble().also { if (!it.isFinite() || it !in 0.0..1_000_000.0) bad(field, "number must be finite and at most 1000000") })
        is Map<*, *> -> {
            val expression = value.asMap(field, field)
            expression.only(setOf("expr"), field)
            val source = expression.string("expr", field)
            try { Expression.evaluate(source, emptyMap(), validateOnly = true) }
            catch (failure: IllegalArgumentException) { bad(field, failure.message ?: "invalid expression") }
            Numeric.Expression(source)
        }
        else -> bad(field, "expected a number or {expr: ...}")
    }

    private fun ticks(text: String, field: String): Int {
        val match = DURATION.matchEntire(text) ?: bad(field, "expected a duration such as 250ms, 2s, or 1m")
        val amount = match.groupValues[1].toDoubleOrNull() ?: bad(field, "invalid duration")
        val milliseconds = amount * when (match.groupValues[2]) { "ms" -> 1.0; "s" -> 1000.0; else -> 60000.0 }
        if (!milliseconds.isFinite() || milliseconds < 0 || milliseconds > 3_600_000) bad(field, "duration must be between 0 and 1h")
        return kotlin.math.ceil(milliseconds / 50.0).toInt()
    }

    private data class Doc(val file: String, val data: Map<String, Any?>)
    private companion object {
        val PACK_ID = Regex("[a-z0-9_.-]+")
        val LOCAL_ID = Regex("[a-z0-9_./-]+")
        val GLOBAL_ID = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
        val DURATION = Regex("(0|[0-9]+(?:\\.[0-9]+)?)(ms|s|m)")
    }
}

private class InvalidManifest(val field: String, message: String) : RuntimeException(message)
private fun bad(field: String, problem: String): Nothing = throw InvalidManifest(field, problem)
private fun Any?.asMap(name: String, field: String): Map<String, Any?> {
    val source = this as? Map<*, *> ?: bad(field, "$name must be a mapping")
    if (source.keys.any { it !is String }) bad(field, "mapping keys must be strings")
    @Suppress("UNCHECKED_CAST")
    return source as Map<String, Any?>
}
private fun Any?.asString(field: String): String = (this as? String)?.takeIf { it.isNotBlank() } ?: bad(field, "expected a nonempty string")
private fun Map<String, Any?>.string(key: String, field: String): String = this[key].asString(if (field == "$") key else "$field.$key")
private fun Map<String, Any?>.integer(key: String, field: String): Int {
    val value = this[key]
    if (value !is Int && value !is Long) bad(if (field == "$") key else "$field.$key", "expected an integer")
    val long = (value as Number).toLong()
    if (long !in Int.MIN_VALUE..Int.MAX_VALUE) bad(if (field == "$") key else "$field.$key", "integer is out of range")
    return long.toInt()
}
private fun Map<String, Any?>.number(key: String, field: String): Double {
    val number = (this[key] as? Number)?.toDouble() ?: bad(if (field == "$") key else "$field.$key", "expected a number")
    if (!number.isFinite()) bad(if (field == "$") key else "$field.$key", "number must be finite")
    return number
}
private fun Map<String, Any?>.listOrEmpty(key: String, field: String): List<Any?> {
    if (key !in this) return emptyList()
    val value = this[key]
    return value as? List<*> ?: bad(if (field == "$") key else "$field.$key", "expected a list")
}
private fun Map<String, Any?>.only(fields: Set<String>, field: String) {
    val unknown = keys.firstOrNull { it !in fields }
    if (unknown != null) bad(if (field == "$") unknown else "$field.$unknown", "unknown field")
}
