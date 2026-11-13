package com.openminis.app.autogroup

/**
 * Standalone executable self test for [AutoGroupPlanner].
 *
 * It lives in the production source set (not `src/test`) on purpose so the algorithm owner
 * can run it directly with `kotlinc`/`java` without pulling in a test framework. It depends
 * only on the Kotlin standard library (`println` + [AssertionError]); no JUnit, no Android.
 *
 * Run with: `kotlinc AutoGroupPlanner.kt AutoGroupPlannerSelfTest.kt -include-runtime -d out.jar && java -jar out.jar`
 */
object AutoGroupPlannerSelfTest {

    private var passed = 0

    private fun check(name: String, condition: Boolean, detail: () -> String = { "" }) {
        if (condition) {
            passed++
            println("PASS  $name")
        } else {
            println("FAIL  $name :: ${detail()}")
            throw AssertionError("Self test failed: $name :: ${detail()}")
        }
    }

    private fun checkEq(name: String, actual: Any?, expected: Any?) {
        check(name, actual == expected) { "expected=$expected actual=$actual" }
    }

    private fun item(
        entryId: String,
        modelId: String = "m",
        displayName: String = "M",
        providerInstanceId: String = "p1",
        providerLabel: String = "OpenAI",
        isHidden: Boolean = false,
    ) = AutoGroupInput(
        entryId = entryId,
        modelId = modelId,
        displayName = displayName,
        providerInstanceId = providerInstanceId,
        providerLabel = providerLabel,
        isHidden = isHidden,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        emptyInput()
        sameCapabilityAcrossChannelsIsOneGroup()
        singleModalityNaming()
        dualModalityNaming()
        audioVsVisionTokens()
        hiddenFiltering()
        entryIdDeduplication()
        blankProviderStillGroupedByCapability()
        outputOrderingStable()
        rebuildMergeSameNames()
        prefixValue()

        println("ALL $passed CHECKS PASSED")
    }

    private fun emptyInput() {
        checkEq("AUTO_PREFIX value", AutoGroupPlanner.AUTO_PREFIX, "[自动] ")
        checkEq(
            "empty input -> empty plan",
            AutoGroupPlanner.plan(emptyList(), rebuild = false, existingNames = emptyList()),
            emptyList<AutoGroupPlan>(),
        )
    }

    /**
     * THE core requirement: channels must not partition capability. Three vision-capable
     * models reached through three different providers all belong to one group.
     */
    private fun sameCapabilityAcrossChannelsIsOneGroup() {
        val items = listOf(
            item("x1", modelId = "gpt-4o", providerInstanceId = "p1", providerLabel = "OpenAI"),
            item("x2", modelId = "claude-3-5-sonnet", providerInstanceId = "p2", providerLabel = "Anthropic"),
            item("x3", modelId = "gemini-1.5-pro", providerInstanceId = "p3", providerLabel = "Google"),
        )
        val plan = AutoGroupPlanner.plan(items, rebuild = false, existingNames = emptyList())
        checkEq("cross-channel vision -> exactly one group", plan.size, 1)
        checkEq("cross-channel group name carries no provider", plan[0].name, "[自动] 视觉")
        checkEq("cross-channel members unified", plan[0].entryIds, listOf("x1", "x2", "x3"))
    }

    private fun singleModalityNaming() {
        val items = listOf(
            item("e1", modelId = "gpt-4o"),
            item("e2", modelId = "gpt-4o-mini"),
        )
        val plan = AutoGroupPlanner.plan(items, rebuild = false, existingNames = emptyList())
        checkEq("single modality -> one group", plan.size, 1)
        checkEq("group named after capability, not provider", plan[0].name, "[自动] 视觉")
        checkEq("members preserved", plan[0].entryIds, listOf("e1", "e2"))
    }

    private fun dualModalityNaming() {
        val items = listOf(
            item("e1", modelId = "gpt-4o", providerInstanceId = "p1", providerLabel = "OpenAI"),
            item("e2", modelId = "dall-e-3", providerInstanceId = "p1", providerLabel = "OpenAI"),
        )
        val plan = AutoGroupPlanner.plan(items, rebuild = false, existingNames = emptyList())
        checkEq("dual modality -> two groups", plan.size, 2)
        checkEq("vision group name", plan[0].name, "[自动] 视觉")
        checkEq("image group name", plan[1].name, "[自动] 图像")
        checkEq("vision members", plan[0].entryIds, listOf("e1"))
        checkEq("image members", plan[1].entryIds, listOf("e2"))
    }

    private fun audioVsVisionTokens() {
        checkEq(
            "modalityOf gpt-4o -> VISION",
            AutoGroupPlanner.modalityOf("gpt-4o", "GPT-4o"),
            AutoGroupModality.VISION,
        )
        checkEq(
            "modalityOf gpt-4o-audio -> AUDIO",
            AutoGroupPlanner.modalityOf("gpt-4o-audio", "GPT-4o Audio"),
            AutoGroupModality.AUDIO,
        )
        checkEq(
            "modalityOf gpt-4o-realtime -> AUDIO",
            AutoGroupPlanner.modalityOf("gpt-4o-realtime-preview", "Realtime"),
            AutoGroupModality.AUDIO,
        )
        checkEq(
            "modalityOf whisper -> AUDIO",
            AutoGroupPlanner.modalityOf("whisper-1", "Whisper"),
            AutoGroupModality.AUDIO,
        )
        checkEq(
            "modalityOf dall-e -> IMAGE",
            AutoGroupPlanner.modalityOf("dall-e-3", "DALL-E 3"),
            AutoGroupModality.IMAGE,
        )
        checkEq(
            "modalityOf claude-3 -> VISION",
            AutoGroupPlanner.modalityOf("claude-3-5-sonnet", "Claude 3.5 Sonnet"),
            AutoGroupModality.VISION,
        )
        checkEq(
            "modalityOf gemini -> VISION",
            AutoGroupPlanner.modalityOf("gemini-1.5-pro", "Gemini Pro"),
            AutoGroupModality.VISION,
        )
        checkEq(
            "modalityOf unknown -> TEXT",
            AutoGroupPlanner.modalityOf("llama-3-70b", "Llama 3"),
            AutoGroupModality.TEXT,
        )
        checkEq(
            "modalityOf displayName signal only",
            AutoGroupPlanner.modalityOf("", "Vision Pro"),
            AutoGroupModality.VISION,
        )
        checkEq(
            "modalityOf qwen-vl -> VISION",
            AutoGroupPlanner.modalityOf("qwen-vl-max", "Qwen-VL"),
            AutoGroupModality.VISION,
        )
    }

    private fun hiddenFiltering() {
        val items = listOf(
            item("e1", modelId = "gpt-4o"),
            item("e2", modelId = "gpt-4o", isHidden = true),
        )
        val plan = AutoGroupPlanner.plan(items, rebuild = false, existingNames = emptyList())
        checkEq("hidden filtered -> one group", plan.size, 1)
        checkEq("hidden entry removed", plan[0].entryIds, listOf("e1"))
    }

    private fun entryIdDeduplication() {
        val items = listOf(
            item("dup", modelId = "gpt-4o", providerInstanceId = "p1", providerLabel = "OpenAI"),
            item("dup", modelId = "dall-e-3", providerInstanceId = "p2", providerLabel = "Other"),
        )
        val plan = AutoGroupPlanner.plan(items, rebuild = false, existingNames = emptyList())
        // First occurrence wins: "dup" is a vision entry, the image duplicate is dropped.
        checkEq("dedup -> only one group survives", plan.size, 1)
        checkEq("dedup group name", plan[0].name, "[自动] 视觉")
        checkEq("dedup members", plan[0].entryIds, listOf("dup"))
    }

    /**
     * A blank provider used to dump entries into a separate "ungrouped" bucket. Now that
     * capability is the only key, a blank provider is simply irrelevant: the entry is grouped
     * by what it can do like any other.
     */
    private fun blankProviderStillGroupedByCapability() {
        val items = listOf(
            item("e1", modelId = "gpt-4o", providerInstanceId = "", providerLabel = ""),
            item("e2", modelId = "whisper-1", providerInstanceId = "", providerLabel = ""),
        )
        val plan = AutoGroupPlanner.plan(items, rebuild = false, existingNames = emptyList())
        checkEq("blank provider -> capability buckets only", plan.size, 2)
        checkEq("blank provider vision bucket", plan[0].name, "[自动] 视觉")
        checkEq("blank provider audio bucket", plan[1].name, "[自动] 语音")
        checkEq("blank provider members kept", plan[0].entryIds, listOf("e1"))
    }

    private fun outputOrderingStable() {
        val items = listOf(
            item("z2", modelId = "llama-3", providerInstanceId = "pz", providerLabel = "Zeta"),
            item("a1", modelId = "gpt-4o", providerInstanceId = "pa", providerLabel = "Alpha"),
            item("a2", modelId = "dall-e-3", providerInstanceId = "pa", providerLabel = "Alpha"),
            item("a3", modelId = "whisper-1", providerInstanceId = "pa", providerLabel = "Alpha"),
            item("m1", modelId = "gemini", providerInstanceId = "pm", providerLabel = "Mid"),
        )
        val plan = AutoGroupPlanner.plan(items, rebuild = false, existingNames = emptyList())
        val names = plan.map { it.name }
        checkEq(
            "ordering is modality ordinal, provider-independent",
            names,
            listOf(
                "[自动] 视觉",
                "[自动] 图像",
                "[自动] 语音",
                "[自动] 文本",
            ),
        )
        // Vision bucket must have absorbed both providers, in input order.
        checkEq("vision bucket merges channels", plan[0].entryIds, listOf("a1", "m1"))
        // Run twice to confirm determinism.
        val plan2 = AutoGroupPlanner.plan(items, rebuild = false, existingNames = emptyList())
        checkEq("deterministic repeat", plan2.map { it.name }, names)
    }

    private fun rebuildMergeSameNames() {
        val items = listOf(
            item("e1", modelId = "gpt-4o", providerInstanceId = "p1", providerLabel = "OpenAI"),
            item("e2", modelId = "dall-e-3", providerInstanceId = "p1", providerLabel = "OpenAI"),
        )
        val merged = AutoGroupPlanner.plan(
            items,
            rebuild = false,
            existingNames = listOf("[自动] 视觉"),
        )
        val rebuilt = AutoGroupPlanner.plan(
            items,
            rebuild = true,
            existingNames = listOf("[自动] 视觉"),
        )
        checkEq("merge names == rebuild names", merged.map { it.name }, rebuilt.map { it.name })
        checkEq(
            "existingNames does not rename",
            merged.map { it.name },
            listOf("[自动] 视觉", "[自动] 图像"),
        )
    }

    private fun prefixValue() {
        checkEq("prefix exact", AutoGroupPlanner.AUTO_PREFIX, "[自动] ")
        check("prefix has trailing space", AutoGroupPlanner.AUTO_PREFIX.endsWith(" "))
    }
}

/** Top level entry point so the file can be run as `java -jar` or via an IDE run config. */
fun main() = AutoGroupPlannerSelfTest.main(arrayOf())
