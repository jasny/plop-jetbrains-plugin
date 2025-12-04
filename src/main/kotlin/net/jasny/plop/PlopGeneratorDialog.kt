package net.jasny.plop

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonPrimitive
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.*
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.*

/**
 * Dialog that renders Inquirer-style prompts for a Plop generator and collects answers.
 * No validation yet; default values are prefilled when available.
 */
class PlopGeneratorDialog(
    project: Project?,
    private val generator: PlopCli.PlopGeneratorDescription
) : DialogWrapper(project, true) {

    private val fields: MutableMap<String, JComponent> = LinkedHashMap()

    init {
        title = "Plop: ${generator.name}"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())

        // Header with padding and clearer title styling
        val header = JPanel(BorderLayout())
        val nameLabel = if (generator.description.isNotBlank()) {
            JBLabel(generator.description)
        } else {
            JBLabel(generator.name)
        }
        // Slightly larger and bold-ish title
        nameLabel.font = nameLabel.font.deriveFont((nameLabel.font.style or Font.BOLD), nameLabel.font.size2D + 1f)
        header.add(nameLabel, BorderLayout.NORTH)

        header.border = JBUI.Borders.empty(10, 12, 8, 12) // top/right/bottom/left padding with small bottom gap
        panel.add(header, BorderLayout.NORTH)

        // Build form using IntelliJ UI DSL with two columns: label + component
        val formPanel = panel {
            for (p in generator.prompts) {
                val labelText = p.message ?: p.name ?: (p.type ?: "")
                val comp = createComponentForPrompt(p)
                // store component by name for answer collection
                p.name?.let { fields[it] = comp }

                row(labelText) {
                    cell(comp)
                        .align(AlignX.FILL)
                        .resizableColumn()
                }
            }
        }
        formPanel.border = JBUI.Borders.empty(0, 12, 12, 12)

        // Conditional scroll: only when many prompts
        val center: JComponent = if (generator.prompts.size > 8) {
            JBScrollPane(formPanel).apply {
                border = JBUI.Borders.empty()
            }
        } else formPanel

        panel.add(center, BorderLayout.CENTER)

        // Let dialog pack naturally but avoid being too tiny
        panel.minimumSize = Dimension(600, 0)
        return panel
    }

    private fun createComponentForPrompt(p: PlopCli.Prompt): JComponent {
        val type = p.type ?: "input"
        val def = p.default
        val choices = p.choices

        fun jsonToString(el: JsonElement?): String? = when (el) {
            null, JsonNull.INSTANCE -> null
            is JsonPrimitive -> el.asString
            else -> el.toString()
        }

        return when (type) {
            "number" -> JBTextField(jsonToString(def) ?: "")
            "confirm" -> JBCheckBox().apply { isSelected = (def?.asBoolean ?: false) }
            "list", "rawList", "expand" -> {
                val items: List<String> = toChoiceStrings(choices)
                val combo = ComboBox(items.toTypedArray())
                val defStr = jsonToString(def)
                if (defStr != null) combo.item = defStr
                combo
            }
            "checkbox" -> {
                val items: List<String> = toChoiceStrings(choices)
                val list = JBList(items)
                list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
                // Preselect defaults if present as array or string
                when {
                    def != null && def.isJsonArray -> {
                        val set = def.asJsonArray.map { jsonToString(it) }.toSet()
                        val indices = items.mapIndexedNotNull { idx, s -> if (set.contains(s)) idx else null }.toIntArray()
                        list.selectedIndices = indices
                    }
                    def != null -> {
                        val str = jsonToString(def)
                        val idx = items.indexOf(str)
                        if (idx >= 0) list.selectedIndex = idx
                    }
                }
                JBScrollPane(list).apply { viewport.view = list }
            }
            "password" -> JBPasswordField().apply { text = (jsonToString(def) ?: "") }
            "editor" -> JBScrollPane(JBTextArea(jsonToString(def) ?: "", 5, 40))
            else -> JBTextField(jsonToString(def) ?: "") // input default
        }
    }

    private fun toChoiceStrings(choices: JsonElement?): List<String> {
        if (choices == null || !choices.isJsonArray) return emptyList()
        val arr = choices.asJsonArray
        val result = ArrayList<String>(arr.size())
        for (el in arr) {
            when {
                el.isJsonPrimitive -> result.add(el.asString)
                el.isJsonObject -> {
                    val obj = el.asJsonObject
                    val name = if (obj.has("name")) obj.get("name").asString else obj.get("value")?.asString
                    val value = if (obj.has("value")) obj.get("value").asString else name
                    result.add(value ?: obj.toString())
                }
                else -> result.add(el.toString())
            }
        }
        return result
    }

    /** Returns null if cancelled; otherwise a map of prompt name to collected answer. */
    fun showAndGetAnswers(): Map<String, Any?>? {
        if (!showAndGet()) return null
        val answers = LinkedHashMap<String, Any?>()
        val gson = Gson()
        for (p in generator.prompts) {
            val name = p.name ?: continue
            val comp = fields[name] ?: continue
            val value: Any? = when (comp) {
                is JBCheckBox -> comp.isSelected
                is JBScrollPane -> {
                    val view = comp.viewport.view
                    when (view) {
                        is JBList<*> -> view.selectedValuesList.toList()
                        is JBTextArea -> view.text
                        else -> null
                    }
                }
                is ComboBox<*> -> comp.item
                is JBPasswordField -> String(comp.password)
                is JBTextField -> comp.text
                else -> null
            }
            answers[name] = value
        }
        return answers
    }
}
