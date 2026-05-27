package com.mobilegem.gemma.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MemoryScreen(viewModel: MemoryViewModel, onOpenChat: () -> Unit) {
    val state by viewModel.uiState.collectAsState()

    if (state.selectedProjectId == null) {
        ProjectListView(
            projects = state.projects.map { it.id to it.name },
            onCreate = viewModel::createProject,
            onSelect = viewModel::selectProject,
        )
    } else {
        ProjectDetailView(
            state = state,
            onBack = viewModel::clearSelection,
            onCreateSession = viewModel::createSession,
            onOpenSession = { id -> viewModel.openSession(id); onOpenChat() },
            onLearn = viewModel::runSelfLearning,
            onAddSkill = viewModel::addSkill,
            onToggleSkill = viewModel::toggleSkill,
            onDeleteSkill = viewModel::deleteSkill,
            onDeleteMemory = viewModel::deleteMemory,
        )
    }
}

@Composable
private fun ProjectListView(
    projects: List<Pair<Long, String>>,
    onCreate: (String) -> Unit,
    onSelect: (Long) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp), Arrangement.spacedBy(12.dp)) {
        Text("Projects")
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("New project") }, modifier = Modifier.weight(1f),
            )
            Button(onClick = { if (name.isNotBlank()) { onCreate(name); name = "" } }) {
                Text("Add")
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(projects) { (id, label) ->
                Card(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { onSelect(id) }) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun ProjectDetailView(
    state: MemoryUiState,
    onBack: () -> Unit,
    onCreateSession: (String) -> Unit,
    onOpenSession: (Long) -> Unit,
    onLearn: (Long) -> Unit,
    onAddSkill: (String, String) -> Unit,
    onToggleSkill: (com.mobilegem.gemma.memory.db.Skill) -> Unit,
    onDeleteSkill: (Long) -> Unit,
    onDeleteMemory: (Long) -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().padding(16.dp), Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("< Projects") }
        state.message?.let { Text(it) }
        TabRow(selectedTabIndex = tab) {
            listOf("Sessions", "Skills", "Memory").forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
            }
        }
        when (tab) {
            0 -> SessionsTab(state, onCreateSession, onOpenSession, onLearn)
            1 -> SkillsTab(state, onAddSkill, onToggleSkill, onDeleteSkill)
            else -> MemoryTab(state, onDeleteMemory)
        }
    }
}

@Composable
private fun SessionsTab(
    state: MemoryUiState,
    onCreateSession: (String) -> Unit,
    onOpenSession: (Long) -> Unit,
    onLearn: (Long) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize(), Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text("New session") }, modifier = Modifier.weight(1f),
            )
            Button(onClick = { if (title.isNotBlank()) { onCreateSession(title); title = "" } }) {
                Text("Add")
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.sessions) { session ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), Arrangement.spacedBy(4.dp)) {
                        Text(session.title)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onOpenSession(session.id) }) {
                                Text("Open in Chat")
                            }
                            TextButton(
                                enabled = !state.busy,
                                onClick = { onLearn(session.id) },
                            ) { Text("End & learn") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillsTab(
    state: MemoryUiState,
    onAddSkill: (String, String) -> Unit,
    onToggleSkill: (com.mobilegem.gemma.memory.db.Skill) -> Unit,
    onDeleteSkill: (Long) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize(), Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Skill name") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = instructions, onValueChange = { instructions = it },
            label = { Text("Instructions") }, modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            if (name.isNotBlank() && instructions.isNotBlank()) {
                onAddSkill(name, instructions); name = ""; instructions = ""
            }
        }) { Text("Add skill") }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.skills) { skill ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(skill.name)
                            Text(skill.instructions)
                        }
                        Switch(
                            checked = skill.enabled,
                            onCheckedChange = { onToggleSkill(skill) },
                        )
                        TextButton(onClick = { onDeleteSkill(skill.id) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryTab(state: MemoryUiState, onDeleteMemory: (Long) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.memories) { entry ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    Arrangement.SpaceBetween,
                ) {
                    Text(entry.content, Modifier.weight(1f))
                    TextButton(onClick = { onDeleteMemory(entry.id) }) { Text("Delete") }
                }
            }
        }
    }
}
