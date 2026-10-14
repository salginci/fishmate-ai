package com.fishmate.ai.ui.screens.tackle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backpack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fishmate.ai.R
import com.fishmate.ai.data.local.entity.TackleEntity
import com.fishmate.ai.data.remote.dto.TackleItemCreate
import com.fishmate.ai.ui.components.EmptyState
import com.fishmate.ai.ui.components.ErrorBanner
import com.fishmate.ai.ui.components.FishMateCard
import com.fishmate.ai.ui.components.SectionHeader

@Composable
fun TackleScreen(viewModel: TackleViewModel = hiltViewModel()) {
    val grouped by viewModel.grouped.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.tackle_add))
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (grouped.isEmpty()) {
                // No starter-set action here any more. A tap used to fill the
                // box with a template kit — rod, reel, jigheads, a minnow —
                // and from then on every recommendation read an inventory
                // describing gear the angler had never owned. An empty box is
                // the honest state, and it is the one that makes the
                // assistant ask.
                EmptyState(
                    icon = Icons.Default.Backpack,
                    title = stringResource(R.string.tackle_empty_title),
                    body = stringResource(R.string.tackle_empty_body),
                    actionLabel = stringResource(R.string.tackle_add),
                    onAction = { showAdd = true },
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    error?.let {
                        item { ErrorBanner(message = it.message) }
                    }
                    grouped.forEach { (category, items) ->
                        item(key = "header-$category") {
                            SectionHeader(categoryLabel(category))
                        }
                        items(items, key = { it.id }) { item ->
                            Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)) {
                                TackleRow(
                                    item = item,
                                    onDelete = { viewModel.delete(item.id) },
                                    onConfirm = {
                                        // Re-saving with the same values is the
                                        // confirmation: the repository clears
                                        // the AI flag on any user edit.
                                        viewModel.update(
                                            item.id,
                                            TackleItemCreate(
                                                category = item.category,
                                                name = item.name,
                                                brand = item.brand,
                                                weightMinG = item.weightMinG,
                                                weightMaxG = item.weightMaxG,
                                                lengthCm = item.lengthCm,
                                                lineRating = item.lineRating,
                                                notes = item.notes,
                                                isFavourite = item.isFavourite,
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddTackleDialog(
            onDismiss = { showAdd = false },
            onAdd = {
                viewModel.add(it)
                showAdd = false
            },
        )
    }

}

@Composable
private fun TackleRow(item: TackleEntity, onDelete: () -> Unit, onConfirm: () -> Unit) {
    FishMateCard {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = item.name, style = MaterialTheme.typography.titleMedium)
                    if (item.isFavourite) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.height(16.dp),
                        )
                    }
                }
                val detail = listOfNotNull(
                    weightLabel(item),
                    item.lineRating,
                    item.fishingStyles.takeIf { it.isNotEmpty() }
                        ?.joinToString(", ") { it.replace('_', ' ') },
                ).joinToString(" · ")
                if (detail.isNotBlank()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // An AI-suggested item is never silently trusted.
                if (item.aiIdentified) {
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(100),
                    ) {
                        TextButton(onClick = onConfirm) {
                            Text(
                                stringResource(R.string.tackle_needs_confirmation),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AddTackleDialog(onDismiss: () -> Unit, onAdd: (TackleItemCreate) -> Unit) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("lure") }
    var weightMin by remember { mutableStateOf("") }
    var weightMax by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tackle_add)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CATEGORIES.take(4).forEach { option ->
                        TextButton(onClick = { category = option }) {
                            Text(
                                text = categoryLabel(option),
                                color = if (category == option) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = weightMin,
                        onValueChange = { weightMin = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("min g") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = weightMax,
                        onValueChange = { weightMax = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("max g") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onAdd(
                        TackleItemCreate(
                            category = category,
                            name = name.trim(),
                            weightMinG = weightMin.toDoubleOrNull(),
                            weightMaxG = weightMax.toDoubleOrNull() ?: weightMin.toDoubleOrNull(),
                        ),
                    )
                },
            ) { Text(stringResource(R.string.tackle_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}


private val CATEGORIES = listOf(
    "rod", "reel", "lure", "jighead", "soft_plastic", "metal_jig",
    "minnow", "line", "leader", "hook", "bait", "accessory",
)

private fun weightLabel(item: TackleEntity): String? {
    val min = item.weightMinG
    val max = item.weightMaxG
    return when {
        min != null && max != null && min != max -> "${fmt(min)}-${fmt(max)} g"
        min != null -> "${fmt(min)} g"
        max != null -> "${fmt(max)} g"
        else -> null
    }
}

private fun fmt(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

private fun categoryLabel(category: String): String =
    category.replace('_', ' ').replaceFirstChar { it.uppercase() }
