package net.jasny.plop

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/**
 * An action representing a single fake generator. When invoked, it shows a
 * notification indicating which generator was selected.
 */
class PlopFakeGeneratorAction(
    private val generatorName: String,
    description: String? = null
) : AnAction(generatorName, description, /* icon = */ null), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Plop Notifications")
            .createNotification(
                "Fake generator selected: $generatorName",
                NotificationType.INFORMATION
            )
            .notify(project)
    }
}
