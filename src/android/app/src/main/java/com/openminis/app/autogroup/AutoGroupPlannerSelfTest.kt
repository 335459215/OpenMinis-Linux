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
        singleProviderSingleModality()
        singleProviderDualModality()
        audioVsVisionTokens()
        hiddenFiltering()
        entryIdDeduplication()
        blankProviderUngrouped()
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

    private fun singleProviderSingleModality() {
        val items = listOf(
            item("e1", modelId = "gpt-4o"),
            item("e2", modelId = "gpt-4o-mini"),
        )
        val plan = AutoGroupPlanner.plan(items, rebuild = false, existingNames = emptyList())
        checkEq("single provider single modality -> one group", plan.size, 1)
        checkEq("group name not split", plan[0].name, "[自动] OpenAI")
        checkEq("members preserved", plan[0].entryIds, listOf("e1", "e2"))
    }

    private fun singleProviderDualModality() {
        val items = listOf(
            item("e1", modelId = "gpt-4o", providerInstanceId = "p1", providerLabel = "OpenAI"),
            item("e2", modelId = "dall-e-3", providerInstanceId = "p1", providerLabel = "OpenAI"),
        )
        val plan = AutoGroupPlanner.plan(items, rebuild = false, existingNames = emptyList())
        checkEq("dual modality -> two groups", plan.size, 2)
        checkEq("vision group name", plan[0].name, "[自动] OpenAI · 视觉")
        checkEq("image group name", plan[1].name, "[自动] OpenAI · 图像")
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
        // "dup" is first seen under OpenAI; the Other provider must not receive it.
        checkEq("dedup -> only one group survives", plan.size, 1)
        checkEq("dedup group name", plan[0].name, "[自动] OpenAI")
        checkEq("dedup members", plan[0].entryIds, listOf("dup"))
    }

    private fun blankProviderUngrouped() {
        val items = listOf(
            item("e1", modelId = "gpt-4o", providerInstanceId = "", providerLabel = ""),
            item("e2", modelId = "llama-3", providerInstanceId = "", providerLabel = ""),
        )
        val plan = AutoGroupPlanner.plan(items, rebuild = false, existingNames = emptyList())
        checkEq("blank provider -> single ungrouped group", plan.size, 1)
        checkEq("ungrouped name", plan[0].name, "[自动] 未分组模型")
        checkEq("ungrouped members", plan[0].entryIds, listOf("e1", "e2"))
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
            "ordering by providerLabel then modality ord",
            names,
            listOf(
                "[自动] Alpha · 视觉",
                "[自动] Alpha · 图像",
                "[自动] Alpha · 语音",
                "[自动] Mid",
                "[自动] Zeta",
            ),
        )
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
            existingNames = listOf("[自动] OpenAI · 视觉"),
        )
        val rebuilt = AutoGroupPlanner.plan(
            items,
            rebuild = true,
            existingNames = listOf("[自动] OpenAI · 视觉"),
        )
        checkEq("merge names == rebuild names", merged.map { it.name }, rebuilt.map { it.name })
        checkEq(
            "existingNames does not rename",
            merged.map { it.name },
            listOf("[自动] OpenAI · 视觉", "[自动] OpenAI · 图像"),
        )
    }

    private fun prefixValue() {
        checkEq("prefix exact", AutoGroupPlanner.AUTO_PREFIX, "[自动] ")
        check("prefix has trailing space", AutoGroupPlanner.AUTO_PREFIX.endsWith(" "))
    }
}

/** Top level entry point so the file can be run as `java -jar` or via an IDE run config. */
fun main() = AutoGroupPlannerSelfTest.main(arrayOf())
