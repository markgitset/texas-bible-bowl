package net.markdrew.biblebowl.app.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.Division
import net.markdrew.biblebowl.api.LoginRequest
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.api.UserDto
import net.markdrew.biblebowl.app.ui.LocalSeason
import net.markdrew.biblebowl.api.schoolYear
import net.markdrew.biblebowl.client.TbbApi

@Composable
fun AuthScreen(api: TbbApi, onSignedIn: (UserDto) -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 460.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Brand()
            Spacer(Modifier.height(20.dp))
            RoundsStrip()
            Spacer(Modifier.height(24.dp))
            AuthCard(api, onSignedIn)
        }
    }
}

@Composable
private fun Brand() {
    Text(
        "Texas Bible Bowl",
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
    val season = LocalSeason.current
    Text(
        "Study ${season.eventScripture} · ${season.schoolYear} Season",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.secondary,
    )
}

@Composable
private fun RoundsStrip() {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Round.entries.forEach { round ->
            AssistChip(
                onClick = {},
                label = { Text(round.displayName) },
                colors = AssistChipDefaults.assistChipColors(
                    labelColor = if (round.openBible) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.secondary,
                ),
            )
        }
    }
}

@Composable
private fun AuthCard(api: TbbApi, onSignedIn: (UserDto) -> Unit) {
    var registering by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var gradeText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (registering) "Create your account" else "Welcome back",
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = email, onValueChange = { email = it },
                label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            if (registering) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = gradeText, onValueChange = { gradeText = it.filter(Char::isDigit) },
                    label = { Text("Grade (3–12, optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                gradeText.toIntOrNull()?.let { g ->
                    Division.forGrade(g)?.let {
                        Text("Division: ${it.displayName}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Button(
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            val resp = if (registering)
                                api.register(RegisterRequest(email.trim(), password, name.trim(), gradeText.toIntOrNull()))
                            else
                                api.login(LoginRequest(email.trim(), password))
                            onSignedIn(resp.user)
                        } catch (e: Throwable) {
                            error = "Error: ${e.message}"
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && email.isNotBlank() && password.length >= 8 &&
                    (!registering || name.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) CircularProgressIndicator(Modifier.height(18.dp))
                else Text(if (registering) "Sign up" else "Sign in")
            }

            TextButton(onClick = { registering = !registering }, modifier = Modifier.fillMaxWidth()) {
                Text(if (registering) "Have an account? Sign in" else "New here? Create an account")
            }

            error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
