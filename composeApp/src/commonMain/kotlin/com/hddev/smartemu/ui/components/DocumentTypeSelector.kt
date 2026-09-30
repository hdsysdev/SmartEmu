package com.hddev.smartemu.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hddev.smartemu.data.DocumentType

/**
 * The kind of document to emulate, as a segmented button: a passport, or one of the bank-card-sized cards.
 * [label] names the choice; [describe] says what the chosen one means, shown below it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentTypeSelector(
    selected: DocumentType,
    onSelected: (DocumentType) -> Unit,
    enabled: Boolean = true,
    label: String = "Document type",
    describe: (DocumentType) -> String = ::technicalDescription,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            DocumentType.entries.forEachIndexed { index, documentType ->
                SegmentedButton(
                    selected = selected == documentType,
                    onClick = { onSelected(documentType) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = DocumentType.entries.size),
                    enabled = enabled,
                    icon = {},
                    label = { Text(text = documentType.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }
        Text(
            text = describe(selected),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun technicalDescription(documentType: DocumentType): String = when (documentType) {
    DocumentType.PASSPORT -> "TD3 booklet, document code P, two 44-character MRZ lines"
    DocumentType.ID_CARD -> "TD1 card, document code ID, three 30-character MRZ lines on the back"
    DocumentType.RESIDENCE_PERMIT -> "TD1 card, document code IR, three 30-character MRZ lines on the back"
}
