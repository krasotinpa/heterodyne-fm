package fm.heterodyne.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp

@Composable
internal fun ParkingMenuItem(text: String, active: Boolean, pick: () -> Unit) {
    val colors = LocalAppearanceColors.current
    Box(Modifier.fillMaxWidth().height(DesignTokens.number("size.parkMenu.item").dp)
        .background(if (active) colors["accentSoft"] else colors["surface"], RoundedCornerShape(DesignTokens.number("radius.m").dp))
        .semantics { selected = active }.clickable(role = Role.Tab, onClick = pick)
        .padding(horizontal = DesignTokens.number("space.section").dp), contentAlignment = Alignment.CenterStart) {
        BasicText(text, style = designTextStyle("body", colors["text"]))
    }
}

@Composable
internal fun EditorIcon(text: String, description: String, click: () -> Unit) {
    val colors = LocalAppearanceColors.current
    Box(Modifier.size(DesignTokens.number("size.editorRow.iconButton").dp)
        .semantics { contentDescription = description }
        .clickable(role = Role.Button, onClick = click), contentAlignment = Alignment.Center) {
        BasicText(text, style = designTextStyle("title", colors["text"]))
    }
}

@Composable
internal fun ParkingCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.background(LocalAppearanceColors.current["surface"], RoundedCornerShape(DesignTokens.number("radius.xl").dp))
        .padding(DesignTokens.number("space.cardPadding").dp),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.number("space.plateGap").dp), content = content)
}

@Composable
fun DeveloperBadge(enabled: Boolean, modifier: Modifier = Modifier) {
    if (!enabled) return
    val colors = LocalAppearanceColors.current
    BasicText("РАЗРАБОТЧИК", modifier
        .background(colors["bg"], RoundedCornerShape(DesignTokens.number("radius.s").dp))
        .border(DesignTokens.number("line.edge").dp, colors["accentText"], RoundedCornerShape(DesignTokens.number("radius.s").dp))
        .padding(DesignTokens.number("space.plateGap").dp), style = designTextStyle("indicator", colors["accentText"]))
}
