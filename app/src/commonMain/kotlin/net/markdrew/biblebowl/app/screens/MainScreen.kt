package net.markdrew.biblebowl.app.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.UserDto
import net.markdrew.biblebowl.app.net.TbbApi

private enum class MainTab(val title: String) {
    STUDY("Study"),
    QUIZ("Quiz"),
    NUMBERS("Numbers"),
    CONTRIBUTE("Contribute"),
    MODERATE("Moderate"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(api: TbbApi, user: UserDto, onSignOut: () -> Unit) {
    val tabs = remember(user) {
        buildList {
            add(MainTab.STUDY)
            add(MainTab.QUIZ)
            add(MainTab.NUMBERS)
            if (Permission.QUESTION_SUBMIT in user.permissions) add(MainTab.CONTRIBUTE)
            if (Permission.QUESTION_MODERATE in user.permissions) add(MainTab.MODERATE)
        }
    }
    var selected by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Texas Bible Bowl", fontWeight = FontWeight.Bold)
                        Text(
                            buildString {
                                append(user.displayName)
                                user.division?.let { append(" · ${it.displayName}") }
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onSignOut) { Text("Sign out") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = selected) {
                tabs.forEachIndexed { i, tab ->
                    Tab(
                        selected = selected == i,
                        onClick = { selected = i },
                        text = { Text(tab.title) },
                    )
                }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(16.dp)) {
                    when (tabs[selected]) {
                        MainTab.STUDY -> StudyScreen(api)
                        MainTab.QUIZ -> QuizScreen(api)
                        MainTab.NUMBERS -> NumbersScreen(api)
                        MainTab.CONTRIBUTE -> ContributeScreen(api)
                        MainTab.MODERATE -> ModerateScreen(api)
                    }
                }
            }
        }
    }
}
