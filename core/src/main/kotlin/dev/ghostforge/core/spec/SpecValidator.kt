package dev.ghostforge.core.spec

/**
 * Semantic validation of an already-parsed specification.
 *
 * Parsing has established shape: known types, known keys, correct primitives.
 * This stage establishes meaning -- that references resolve, bounds hold,
 * defaults are legal values, and merge policies suit the fields they sit on.
 */
object SpecValidator {

    fun validate(spec: AppSpec): ValidationReport {
        val e = ErrorCollector()
        validateHeader(spec, e)
        val datasetsById = validateDatasets(spec, e)
        validateComponents(spec, datasetsById, e)
        return e.report()
    }

    private fun validateHeader(spec: AppSpec, e: ErrorCollector) {
        if (spec.schemaVersion != Limits.SCHEMA_VERSION) {
            e.add(
                ErrorCode.UNSUPPORTED_SCHEMA_VERSION, "schema_version",
                "expected exactly '${Limits.SCHEMA_VERSION}', got '${spec.schemaVersion}'",
            )
        }
        if (!Limits.APP_ID.matches(spec.appId)) {
            e.add(ErrorCode.INVALID_IDENTIFIER, "app_id", "must match ${Limits.APP_ID.pattern}")
        }
        if (spec.name.isEmpty() || spec.name.length > Limits.MAX_NAME_LENGTH) {
            e.add(
                ErrorCode.LENGTH_OUT_OF_BOUNDS, "name",
                "length must be 1..${Limits.MAX_NAME_LENGTH}, got ${spec.name.length}",
            )
        }
        if (spec.description.length > Limits.MAX_DESCRIPTION_LENGTH) {
            e.add(
                ErrorCode.LENGTH_OUT_OF_BOUNDS, "description",
                "length must be 0..${Limits.MAX_DESCRIPTION_LENGTH}, got ${spec.description.length}",
            )
        }
        if (spec.specVersion < Limits.MIN_SPEC_VERSION) {
            e.add(
                ErrorCode.OUT_OF_RANGE, "spec_version",
                "must be >= ${Limits.MIN_SPEC_VERSION}, got ${spec.specVersion}",
            )
        }
    }

    /** @return declared datasets by id, for reference resolution by components. */
    private fun validateDatasets(spec: AppSpec, e: ErrorCollector): Map<String, Dataset> {
        if (spec.datasets.isEmpty()) {
            e.add(ErrorCode.TOO_FEW, "datasets", "at least one dataset is required")
        }
        if (spec.datasets.size > Limits.MAX_DATASETS) {
            e.add(
                ErrorCode.TOO_MANY, "datasets",
                "at most ${Limits.MAX_DATASETS} allowed, got ${spec.datasets.size}",
            )
        }

        val byId = LinkedHashMap<String, Dataset>()
        var totalFields = 0

        spec.datasets.forEachIndexed { i, ds ->
            val path = "datasets[$i]"
            if (!Limits.IDENTIFIER.matches(ds.id)) {
                e.add(ErrorCode.INVALID_IDENTIFIER, "$path.id", "must match ${Limits.IDENTIFIER.pattern}")
            }
            if (byId.put(ds.id, ds) != null) {
                e.add(ErrorCode.DUPLICATE_ID, "$path.id", "dataset id '${ds.id}' already declared")
            }
            if (ds.fields.isEmpty()) {
                e.add(ErrorCode.TOO_FEW, "$path.fields", "at least one field is required")
            }
            if (ds.fields.size > Limits.MAX_FIELDS_PER_DATASET) {
                e.add(
                    ErrorCode.TOO_MANY, "$path.fields",
                    "at most ${Limits.MAX_FIELDS_PER_DATASET} allowed, got ${ds.fields.size}",
                )
            }
            totalFields += ds.fields.size

            val seenFieldIds = mutableSetOf<String>()
            ds.fields.forEachIndexed { j, f -> validateField(f, "$path.fields[$j]", seenFieldIds, e) }
        }

        if (totalFields > Limits.MAX_TOTAL_FIELDS) {
            e.add(
                ErrorCode.TOO_MANY, "datasets",
                "total fields must be <= ${Limits.MAX_TOTAL_FIELDS}, got $totalFields",
            )
        }
        return byId
    }

    private fun validateField(f: Field, path: String, seenIds: MutableSet<String>, e: ErrorCollector) {
        if (!Limits.IDENTIFIER.matches(f.id)) {
            e.add(ErrorCode.INVALID_IDENTIFIER, "$path.id", "must match ${Limits.IDENTIFIER.pattern}")
        }
        if (!seenIds.add(f.id)) {
            e.add(ErrorCode.DUPLICATE_ID, "$path.id", "field id '${f.id}' already used in this dataset")
        }
        if (f.label.isEmpty() || f.label.length > Limits.MAX_LABEL_LENGTH) {
            e.add(
                ErrorCode.LENGTH_OUT_OF_BOUNDS, "$path.label",
                "length must be 1..${Limits.MAX_LABEL_LENGTH}, got ${f.label.length}",
            )
        }
        validateMergePolicy(f, path, e)
        when (f) {
            is TextField -> validateTextField(f, path, e)
            is NumberField -> validateNumberField(f, path, e)
            is EnumField -> validateEnumField(f, path, e)
            is BooleanField -> Unit
            is TimestampField -> Unit
        }
    }

    /**
     * A merge policy must suit the field it sits on. An ordered merge over free
     * text, or a status transition over a number, would silently corrupt
     * records once disconnected devices reconnect.
     */
    private fun validateMergePolicy(f: Field, path: String, e: ErrorCollector) {
        val allowed: Set<MergePolicy> = when (f) {
            is TextField, is BooleanField ->
                setOf(MergePolicy.LAST_WRITE_WINS, MergePolicy.MANUAL)
            is NumberField, is TimestampField ->
                setOf(MergePolicy.LAST_WRITE_WINS, MergePolicy.MAX, MergePolicy.MIN, MergePolicy.MANUAL)
            is EnumField ->
                setOf(MergePolicy.LAST_WRITE_WINS, MergePolicy.STATUS_TRANSITION, MergePolicy.MANUAL)
        }
        if (f.merge !in allowed) {
            e.add(
                ErrorCode.INCOMPATIBLE_MERGE_POLICY, "$path.merge",
                "${f.merge} is not valid here; allowed: ${allowed.joinToString()}",
            )
        }
    }

    private fun validateTextField(f: TextField, path: String, e: ErrorCollector) {
        if (f.maxLength < 1 || f.maxLength > Limits.MAX_TEXT_LENGTH) {
            e.add(
                ErrorCode.OUT_OF_RANGE, "$path.max_length",
                "must be 1..${Limits.MAX_TEXT_LENGTH}, got ${f.maxLength}",
            )
        }
        val d = f.default
        if (d != null && d.length > f.maxLength) {
            e.add(ErrorCode.INVALID_DEFAULT, "$path.default", "longer than max_length ${f.maxLength}")
        }
    }

    private fun validateNumberField(f: NumberField, path: String, e: ErrorCollector) {
        val min = f.minimum
        val max = f.maximum
        if (min != null && max != null && min > max) {
            e.add(ErrorCode.OUT_OF_RANGE, "$path.minimum", "minimum $min exceeds maximum $max")
        }
        val d = f.default ?: return
        if (min != null && d < min) {
            e.add(ErrorCode.INVALID_DEFAULT, "$path.default", "default $d is below minimum $min")
        }
        if (max != null && d > max) {
            e.add(ErrorCode.INVALID_DEFAULT, "$path.default", "default $d is above maximum $max")
        }
    }

    private fun validateEnumField(f: EnumField, path: String, e: ErrorCollector) {
        if (f.options.size < Limits.MIN_ENUM_OPTIONS) {
            e.add(
                ErrorCode.TOO_FEW, "$path.options",
                "at least ${Limits.MIN_ENUM_OPTIONS} required, got ${f.options.size}",
            )
        }
        if (f.options.size > Limits.MAX_ENUM_OPTIONS) {
            e.add(
                ErrorCode.TOO_MANY, "$path.options",
                "at most ${Limits.MAX_ENUM_OPTIONS} allowed, got ${f.options.size}",
            )
        }
        val seen = mutableSetOf<String>()
        f.options.forEachIndexed { k, opt ->
            if (!Limits.ENUM_OPTION.matches(opt)) {
                e.add(
                    ErrorCode.INVALID_IDENTIFIER, "$path.options[$k]",
                    "must match ${Limits.ENUM_OPTION.pattern}",
                )
            }
            if (!seen.add(opt)) {
                e.add(ErrorCode.DUPLICATE_ID, "$path.options[$k]", "duplicate option '$opt'")
            }
        }
        val d = f.default
        if (d != null && d !in f.options) {
            e.add(ErrorCode.INVALID_DEFAULT, "$path.default", "default '$d' is not one of the options")
        }
    }

    private fun validateComponents(
        spec: AppSpec,
        datasets: Map<String, Dataset>,
        e: ErrorCollector,
    ) {
        if (spec.components.isEmpty()) {
            e.add(ErrorCode.TOO_FEW, "components", "at least one component is required")
        }
        if (spec.components.size > Limits.MAX_COMPONENTS) {
            e.add(
                ErrorCode.TOO_MANY, "components",
                "at most ${Limits.MAX_COMPONENTS} allowed, got ${spec.components.size}",
            )
        }

        spec.components.forEachIndexed { i, c ->
            val path = "components[$i]"
            val ds = datasets[c.dataset]
            if (ds == null) {
                e.add(
                    ErrorCode.UNRESOLVED_REFERENCE, "$path.dataset",
                    "no dataset declared with id '${c.dataset}'",
                )
                return@forEachIndexed
            }
            val fieldsById = ds.fields.associateBy { it.id }
            when (c) {
                is RecordForm -> Unit
                is RecordList -> validateRecordList(c, path, fieldsById, e)
                is StatusCounter -> validateStatusCounter(c, path, fieldsById, e)
            }
        }
    }

    private fun validateRecordList(
        c: RecordList,
        path: String,
        fieldsById: Map<String, Field>,
        e: ErrorCollector,
    ) {
        val seen = mutableSetOf<String>()
        c.columns.forEachIndexed { k, col ->
            if (col !in fieldsById) {
                e.add(
                    ErrorCode.UNRESOLVED_REFERENCE, "$path.columns[$k]",
                    "dataset '${c.dataset}' has no field '$col'",
                )
            }
            if (!seen.add(col)) {
                e.add(ErrorCode.DUPLICATE_ID, "$path.columns[$k]", "duplicate column '$col'")
            }
        }
    }

    /**
     * Grouping is restricted to enum fields. An enum has a bounded option set,
     * so the number of groups is known from the spec alone; grouping by text or
     * number would let record content dictate unbounded work at render time.
     */
    private fun validateStatusCounter(
        c: StatusCounter,
        path: String,
        fieldsById: Map<String, Field>,
        e: ErrorCollector,
    ) {
        val target = fieldsById[c.groupBy]
        if (target == null) {
            e.add(
                ErrorCode.UNRESOLVED_REFERENCE, "$path.group_by",
                "dataset '${c.dataset}' has no field '${c.groupBy}'",
            )
            return
        }
        if (target !is EnumField) {
            e.add(
                ErrorCode.TYPE_MISMATCH, "$path.group_by",
                "group_by must name an enum field; '${c.groupBy}' is ${target::class.simpleName}",
            )
        }
    }
}
